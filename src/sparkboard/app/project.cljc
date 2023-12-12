(ns sparkboard.app.project
  (:require [sparkboard.app.member :as member]
            [clojure.set :as set]
            [sparkboard.authorize :as az]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routing :as routing]
            [sparkboard.schema :as sch :refer [s- ?]]
            [sparkboard.server.datalevin :as dl]
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
     :project/community-actions {s- [:sequential :community-action/as-map]}
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
                                     :entity/title
                                     :entity/created-at
                                     (? :entity/updated-at)
                                     (? :entity/draft?)
                                     (? :entity/archived?)
                                     (? :entity/field-entries)
                                     (? :entity/video)
                                     (? :entity/created-by)
                                     (? :entity/deleted-at)
                                     (? :entity/modified-by)
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
                                     (? :project/team-complete?)]
                                 }
     :community-action/as-map   {s- [:map-of
                                     :community-action/label
                                     :community-action/action
                                     (? :community-action/hint)
                                     (? :community-action.chat)]}
     :community-action/label    {s- :string}
     :community-action/action   (merge sch/keyword
                                       {s- [:enum
                                            :community-action.action/copy-link
                                            :community-action.action/chat]})
     :community-action/hint     {s- :string}}))



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
               {:project/community-actions [:*]}
               :project/sticky?]
             project-id)))

(def btn (v/from-element :div.btn.btn-transp.border-2.py-2.px-3))
(def hint (v/from-element :div.flex.items-center.text-sm {:class "text-primary/70"}))
(def chiclet (v/from-element :div.rounded.px-2.py-1 {:class "bg-primary/5 text-primary/90"}))

(ui/defview manage-community-actions [project actions]
  (forms/with-form [!actions (?actions :many {:community-action/label  ?label
                                              :community-action/action ?action
                                              :community-action/hint   ?hint})]
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
                               [:div.flex [add-btn {:on-click #(forms/add-many! ?actions {:community-action/label label
                                                                                          :community-action/action action})}]]])))]
      [:section.flex-v.gap-3
       [:div
        [:div.font-semibold.text-lg (tr :tr/community-actions)]]
       (when-let [actions (seq ?actions)]
         [:div.flex.flex-wrap.gap-3
          (seq (for [{:syms [?label ?action ?hint]} actions]
                 [radix/tooltip @?hint
                  [:div.default-ring.rounded.inline-flex.items-center.divide-x
                   {:key @?label}
                   [:div.p-3.whitespace-nowrap @?label]]]))])
       [:div.bg-gray-100.rounded.p-3.gap-3.flex-v
        [:div.text-base.text-gray-500 (tr :tr/community-actions-add)]
        [:div.grid.gap-3 {:style {:grid-template-columns "0fr 0fr 1fr"}}
         (sample (str "🔗 " (tr :tr/share)) "LINK")
         (sample (str "🤝 " (tr :tr/join-our-team)) "CHAT")
         (sample (str "💰 " (tr :tr/invest)) "CHAT")
         (forms/with-form [!new-action {:community-action/label ?label :community-action/action ?action :community-action/hint ?hint}]
           (let [!label-ref (h/use-ref)]
             [:form.contents {:on-submit
                              (fn [^js e]
                                (.preventDefault e)
                                (forms/add-many! ?actions @!new-action)
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
                  {:value       (or @?hint "")
                   :on-change   (forms/change-handler ?hint)
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

#?(:cljs
   (defn use-dev-panel [entity]
     (let [!dev-edit? (h/use-state nil)
           can-edit?  (if-some [edit? @!dev-edit?]
                        edit?
                        (validate/editing-role? (:member/roles entity)))]
       [can-edit? (when (ui/dev?)
                    [:div.p-body.bg-gray-100.border-b.flex.gap-3
                     [:div.flex-auto.text-sm [ui/pprinted (:member/roles entity)]]
                     [radix/select-menu {:value           @!dev-edit?
                                         :on-value-change (partial reset! !dev-edit?)
                                         :can-edit?       true
                                         :options         [{:value nil :text "Current User"}
                                                           {:value true :text "Editor"}
                                                           {:value false :text "Viewer"}]}]])])))

(q/defquery db:fields [{:keys [board-id]}]
  (-> (q/pull `[~@entity/id-fields
                {:board/project-fields ~field/field-keys}] board-id)
      :board/project-fields))

(def modal-close [radix/dialog-close
                  [:div.flex.items-center.ml-auto.self-stretch.px-body.cursor-default.text-gray-500.hover:text-black [icons/close "icon-lg"]]])

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
        [can-edit? dev-panel] (use-dev-panel project)
        entries (->> project :entity/field-entries (sort-by (comp :field-entry/field :field/order)))]
    [:<>
     dev-panel
     #_[ui/entity-header parent]
     [:div.flex.gap-2.h-14.items-stretch
      [:h1.font-semibold.text-3xl.flex-auto.px-body.flex.items-center
       ;; TODO
       ;; make title editable for `can-edit?` and have it autofocus if title = (tr :tr/untitled)
       ;; - think about how to make the title field editable
       ;; - dotted underline if editable?
       title]
      modal-close]
     [:div.p-body.flex-v.gap-6
      (ui/show-prose description)
      (when badges
        [:section
         (into [:ul]
               (map (fn [bdg] [:li.rounded.bg-badge.text-badge-txt.py-1.px-2.text-sm.inline-flex (:badge/label bdg)]))
               badges)])
      (map #(field/show-entry {:can-edit? can-edit?
                               :entry     %}) entries)
      [:section.flex-v.gap-2.items-start.mt-32
       [manage-community-actions project (:project/community-actions project)]]
      (when-let [vid video]
        [:section [:h3 (tr :tr/video)]
         [video-field vid]])]]))

(q/defx db:new!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} project]
  ;; TODO
  ;; verify that user is allowed to create a new project in parent
  (let [project (dl/new-entity project :project :by account-id)]
    (validate/assert project :project/as-map)
    (db/transact! [project])
    {:entity/id (:entity/id project)}))

(ui/defview new
  {:route       "/new/p/:board-id"
   :view/router :router/modal}
  [{:keys [board-id]}]
  (let [fields (db:fields {:board-id board-id})]
    (forms/with-form [!project {:project/parent board-id
                                :entity/title   ?title}]
      [:<>
       [:h3.flex.items-center.border-b.h-14
        [:div.px-body.flex-auto (tr :tr/new-project)] modal-close]
       [:div.p-body

        [ui/pprinted (map db/touch fields)]
        ]])))
