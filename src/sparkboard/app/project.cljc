(ns sparkboard.app.project
  (:require [sparkboard.app.member :as member]
            [clojure.set :as set]
            [promesa.core :as p]
            [sparkboard.authorize :as az]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routing :as routes]
            [sparkboard.schema :as sch :refer [s- ?]]
            [sparkboard.validate :as validate]
            [sparkboard.ui :as ui]
            [sparkboard.query :as q]
            [sparkboard.app.entity :as entity]
            [sparkboard.app.field :as field]
            [re-db.api :as db]
            [yawn.view :as v]
            [sparkboard.ui.radix :as radix]
            [sparkboard.ui.icons :as icons]
            [inside-out.forms :as forms]
            [yawn.hooks :as h]))

(comment
  (first (db/where [:project/badges]))
  db/*conn*
  )
(sch/register!
  (merge

    {:social/sharing-button {s- [:enum
                                 :social.sharing-button/facebook
                                 :social.sharing-button/twitter
                                 :social.sharing-button/qr-code]}}

    {:request/text {:doc "Free text description of the request"
                    s-   :string}
     :request/map  {s- [:map {:closed true} :request/text]}}

    {:project/open-requests     {:doc "Currently active requests for help"
                                 s-   [:sequential :request/map]},
     :project/team-complete?    {:doc "Project team marked sufficient"
                                 s-   :boolean}
     :project/approved?         {:doc "Set by an admin when :board/new-projects-require-approval? is enabled. Unapproved projects are hidden."
                                 s-   :boolean}
     :project/badges            {:doc "A badge is displayed on a project with similar formatting to a tag. Badges are ad-hoc, not defined at a higher level.",
                                 s-   [:vector :content/badge]}
     :project/number            {:doc  "Number assigned to a project by its board (stored as text because may contain annotations)",
                                 :todo "This could be stored in the board entity, a map of {project, number}, so that projects may participate in multiple boards"
                                 s-    :string}
     :project/admin-description {:doc "A description field only writable by an admin"
                                 s-   :prose/as-map}
     :entity/archived?          {:doc "Marks a project inactive, hidden."
                                 s-   :boolean}
     :project/sticky?           {:doc "Show project with border at top of project list"
                                 s-   :boolean}
     :project/as-map            {s- [:map {:closed true}
                                     :entity/id
                                     :entity/kind
                                     :entity/parent
                                     (? :entity/archived?)
                                     (? :entity/field-entries)
                                     (? :entity/video)
                                     (? :entity/created-by)
                                     (? :entity/deleted-at)
                                     (? :entity/modified-by)
                                     :entity/title
                                     :entity/created-at
                                     :entity/updated-at
                                     (? :member/_entity)
                                     (? [:project/card-classes {:doc          "css classes for card"
                                                                :to-deprecate true}
                                         [:sequential :string]])
                                     (? :project/approved?)
                                     (? :project/badges)
                                     (? :project/number)
                                     (? :project/admin-description)
                                     (? :project/sticky?)
                                     (? :project/open-requests)
                                     (? :entity/description)
                                     (? :project/team-complete?)]}}))



(defn youtube-embed [video-id]
  [:iframe#ytplayer {:type        "text/html" :width 640 :height 360
                     :frameborder 0
                     :src         (str "https://www.youtube.com/embed/" video-id)}])

(defn video-field [[kind v]]
  (case kind
    :video/youtube-id (youtube-embed v)
    :video/youtube-url [:a {:href v} "youtube video"]
    :video/vimeo-url [:a {:href v} "vimeo video"]
    {kind v}))

(comment
  (video-field [:field.video/youtube-sdgurl "gMpYX2oev0M"])
  )

#?(:clj
   (defn db:read
     {:endpoint {:query true}
      :prepare  [az/with-account-id
                 (member/member:log-visit! :project-id)]}
     [{:keys [project-id]}]
     (q/pull `[{:entity/parent ~entity/fields}
               ~@entity/fields
               :project/sticky?]
             project-id)))

(def btn (v/from-element :div.btn.btn-transp.border-2.py-2.px-3))
(def hint (v/from-element :div.flex.items-center.text-sm {:class "text-primary/70"}))
(def chiclet (v/from-element :div.rounded.px-2.py-1 {:class "bg-primary/5 text-primary/90"}))
(ui/defview community-actions [project]
  (forms/with-form [!actions (?actions :many {:label ?label :action ?action :hover-text ?hover-text})]
    (let [action-picker (fn [props]
                          [radix/select-menu
                           (v/merge-props props
                                          {:id          :project-action
                                           :can-edit?   true
                                           :placeholder [:span.text-gray-500 (tr :tr/choose-action)]
                                           :options     [{:text  (tr :tr/copy-link)
                                                          :value "LINK"}
                                                         {:text  (tr :tr/start-chat)
                                                          :value "CHAT"}]})])
          add-btn       (fn [props]
                          (v/x [:button.p-3.text-gray-500.items-center.inline-flex.btn-darken.flex-none.rounded props
                                [icons/plus "icon-sm scale-125"]]))
          sample        (fn [label action]
                          (when-not (some #{label} (map (comp deref '?label) ?actions))
                            (v/x
                              [:<>
                               [:div.default-ring.rounded.inline-flex.divide-x.bg-white
                                [:div.p-3.whitespace-nowrap label]]
                               [action-picker {:value (some-> action str) :disabled true}]
                               [:div.flex [add-btn {:on-click #(forms/add-many! ?actions {'?label label '?action action})}]]])))]
      [:section.flex-v.gap-3
       [:div
        [:div.font-semibold.text-lg (tr :tr/community-actions)]]
       (when-let [actions (seq ?actions)]
         [:div.flex.flex-wrap.gap-3
          (seq (for [{:syms [?label ?action ?hover-text]} actions]
                 [radix/tooltip @?hover-text
                  [:div.default-ring.rounded.inline-flex.items-center.divide-x
                   {:key @?label}
                   [:div.p-3.whitespace-nowrap @?label]]]))])
       [:div.bg-gray-100.rounded.p-3.gap-3.flex-v
        [:div.text-base.text-gray-500 (tr :tr/community-actions-add)]
        [:div.grid.gap-3 {:style {:grid-template-columns "0fr 0fr 1fr"}}
         (sample (str "🔗 " (tr :tr/share)) "LINK")
         (sample (str "🤝 " (tr :tr/join-our-team)) "CHAT")
         (sample (str "💰 " (tr :tr/invest)) "CHAT")
         (forms/with-form [!new-action {:label ?label :action ?action :hover-text ?hover-text}]
           (let [!label-ref (h/use-ref)]
             [:form.contents {:on-submit
                              (fn [^js e]
                                (.preventDefault e)
                                (forms/add-many! ?actions (set/rename-keys @!new-action {:label      '?label
                                                                                         :action     '?action
                                                                                         :hover-text '?hover-text}))
                                (forms/clear! !new-action)
                                (.focus @!label-ref))}
              [:input.default-ring.form-text.rounded.p-3
               {:ref         !label-ref
                :value       (or @?label "")
                :on-change   (forms/change-handler ?label)
                :style       {:min-width 150}
                :placeholder (str (tr :tr/other) "...")}]
              [action-picker {}]
              [:div.flex.gap-3
               (when @?label
                 [:input.text-gray-500.bg-gray-200.rounded.p-3.flex-auto.focus-ring
                  {:value       (or @?hover-text "")
                   :on-change   (forms/change-handler ?hover-text)
                   :style       {:min-width 150}
                   :placeholder (tr :tr/hover-text)}])
               [add-btn {:type "submit"}]]]))]]

       ])))

(q/defquery db:read
  {:prepare [(az/with-roles :project-id)]}
  [{:keys [project-id]}]
  (q/pull `[~@entity/fields
            {:entity/field-entries [:*]}
            {:entity/parent
             [~@entity/fields
              {:board/project-fields ~field/field-keys}]}] project-id))

(ui/defview show
  {:route       "/p/:project-id"
   :view/router :router/modal}
  [params]
  (let [{:as          project
         :entity/keys [title
                       description
                       video]
         :keys        [:entity/parent
                       :project/badges]} (db:read params)
        !dev-edit? (h/use-state nil)
        can-edit?  (if-some [edit? @!dev-edit?]
                     edit?
                     (validate/editing-role? (:member/roles project)))
        entries    (->> project :entity/field-entries (sort-by (comp :field-entry/field :field/order)))]
    [:<>
     (when (ui/dev?)
       [:div.p-body.bg-gray-100.border-b.flex.gap-3
        [:div.flex-auto.text-sm [ui/pprinted (:member/roles project)]]
        [radix/select-menu {:value           @!dev-edit?
                            :on-value-change (partial reset! !dev-edit?)
                            :can-edit?       true
                            :options         [{:value nil :text "Current User"}
                                              {:value true :text "Editor"}
                                              {:value false :text "Viewer"}]}]])
     #_[ui/entity-header parent]
     [:div.p-body.flex-v.gap-6
      [:div.flex.items-start.gap-2
       [:h1.font-semibold.text-3xl.flex-auto title]
       #_[radix/dialog-close [icons/close "w-8 h-8 -mr-2 -mt-1 text-gray-500 hover:text-black"]]]
      (ui/show-prose description)
      (when badges
        [:section
         (into [:ul]
               (map (fn [bdg] [:li.rounded.bg-badge.text-badge-txt.py-1.px-2.text-sm.inline-flex (:badge/label bdg)]))
               badges)])
      (map #(field/show-entry {:can-edit? can-edit?
                               :parent    project
                               :entry     %}) entries)
      [:section.flex-v.gap-2.items-start.mt-32
       [community-actions project]]
      (when-let [vid video]
        [:section [:h3 (tr :tr/video)]
         [video-field vid]])]]))
