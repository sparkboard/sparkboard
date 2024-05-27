(ns sb.app.project.ui
  (:require [inside-out.forms :as forms]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.entity.data :as entity.data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.project.data :as data]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.color :as color]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [re-db.api :as db]))

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
                                          {:id              :project-action
                                           :placeholder     [:span.text-gray-500 (t :tr/choose-action)]
                                           :field/can-edit? true
                                           :field/options   [{:text  (t :tr/copy-link)
                                                              :value "LINK"}
                                                             {:text  (t :tr/start-chat)
                                                              :value "CHAT"}]})])
          add-btn       (fn [props]
                          (v/x [:button.p-3.text-gray-500.items-center.inline-flex.btn-darken.flex-none.rounded props
                                [icons/plus "icon-sm scale-125"]]))
          sample        (fn [label action]
                          (when-not (some #{label} (map (comp deref '?label) ?actions))
                            (v/x
                              [:<>
                               [:div.default-ring.rounded.inline-flex.divide-x.bg-white.items-center
                                [:div.p-3.whitespace-nowrap label]]
                               [action-picker {:value (some-> action str) :disabled true}]
                               [:div.flex [add-btn {:on-click #(forms/add-many! ?actions {:community-action/label  label
                                                                                          :community-action/action action})}]]])))]
      [:section.flex-v.gap-3
       [:div
        [:div.font-semibold.text-lg (t :tr/community-actions)]]
       (when-let [actions (seq ?actions)]
         [:div.flex.flex-wrap.gap-3
          (seq (for [{:syms [?label ?action ?hint]} actions]
                 [radix/tooltip @?hint
                  [:div.default-ring.default-ring-hover.rounded.inline-flex.items-center.divide-x
                   {:key @?label}
                   [:div.p-3.whitespace-nowrap @?label]]]))])
       [:div.bg-gray-100.rounded.p-3.gap-3.flex-v
        [:div.text-base.text-gray-500 (t :tr/community-actions-add)]
        [:div.grid.gap-3 {:style {:grid-template-columns "0fr 0fr 1fr"}}
         (sample (str "🔗 " (t :tr/share)) "LINK")
         (sample (str "🤝 " (t :tr/join-our-team)) "CHAT")
         (sample (str "💰 " (t :tr/invest)) "CHAT")
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
                :placeholder (str (t :tr/other) "...")}]
              [action-picker {}]
              [:div.flex.gap-3
               (when @?label
                 [:input.text-gray-500.bg-gray-200.rounded.p-3.flex-auto.focus-ring
                  {:value       (or @?hint "")
                   :on-change   (forms/change-handler ?hint)
                   :style       {:min-width 150}
                   :placeholder (t :tr/hover-text)}])
               [add-btn {:type "submit"}]]]))]]

       ])))


(defn resolve-tag [parent tag-id]
  (-> parent :entity/member-tags (u/find-first #(= tag-id (:tag/id %)))))

(defn resolved-tags [board-membership]
  (mapv #(resolve-tag (:membership/entity board-membership) (:tag/id %))
        (:entity/tags board-membership)))

(ui/defview project-members [project props]
  ;; todo
  ;; 1. pass in all-roles to know if we are an a board-admin
  ;; 2. add colors for tags
  ;; 3. button for adding a new member via searching this board
  ;; 4. hover to see member details
  ;; 5. fix chat
  [:div.field-wrapper
   [:div.field-label (t :tr/team)]
   [:div.grid.grid-cols-2.gap-6
    (for [member (->> (:membership/_entity project)
                      (sort-by u/compare:desc :entity/created-at))
          :let [board-membership (-> member :membership/member)
                {:as account :keys [account/display-name]} (-> board-membership :membership/member)]]
      [:div.flex.items-center.gap-2
       {:key      (:entity/id member)
        :on-click #(routing/nav! (routing/entity-route board-membership 'ui/show))}
       [:img.object-cover.rounded.w-12.h-12 {:src (asset.ui/asset-src (:image/avatar account) :avatar)}]
       [:div.flex-v.gap-1
        display-name
        [:div.flex.flex-wrap.gap-2
         (for [{:as tag :tag/keys [id label color]} (resolved-tags board-membership)]
           [:div.tag-sm {:style (color/color-pair color)}
            label])]]])]
   (when ((some-fn :role/project-admin :role/board-admin) (:membership/roles props))
     [entity.ui/persisted-attr project :entity/admission-policy props])]
  )

(ui/defview show
  {:route       "/p/:project-id"
   :view/router :router/modal}
  [params]
  (let [project      (data/show params)
        [can-edit? roles dev-panel] (form.ui/use-dev-panel project {"Current User"   (:membership/roles project)
                                                                    "Project Editor" #{:role/project-editor}
                                                                    "Board Admin"    #{:role/board-admin}
                                                                    "Visitor"        #{}} "Current User")
        field-params {:membership/roles roles
                      :field/can-edit?  can-edit?}]
    [:<>
     (when (:entity/draft? project)
       [:div.border-b-2.border-dashed.px-body.py-2.flex-center.gap-3.bg-gray-100
        [:div.mr-auto.text-gray-500 "Draft - only visible to you."]
        [field.ui/action-btn {:on-click #(entity.data/save-attribute! nil (:entity/id project) :entity/draft? false)
                              :classes  {:btn          "btn-primary px-4 py-1"
                                         :progress-bar "text-[rgba(255,255,255,0.5)]"}}
         (t :tr/publish)]])
     [:div.flex-v.gap-6.pb-6.rounded-lg.relative
      (when (:project/sticky? project)
        {:class "outline outline-4"
         :style {:outline-color (-> project :entity/parent :board/sticky-color)
                 :margin        4}})
      ;; title row
      [:div.flex-v.mt-6
       [:div.flex
        [:h1.font-medium.text-2xl.flex-auto.px-body.flex.items-center.pt-6
         [entity.ui/persisted-attr project :entity/title (merge field-params
                                                                {:field/label       false
                                                                 :field/multi-line? false
                                                                 :field/unstyled?   (some-> (:entity/title project)
                                                                                            (not= "Untitled"))})]]


        [:div.flex.px-1.rounded-bl-lg.border-b.border-l.absolute.top-0.right-0
         dev-panel

         (when (:role/board-admin roles)
           ;; - archive
           [radix/dropdown-menu
            {:trigger [:div.flex.items-center [icons/ellipsis-horizontal "rotate-90 icon-gray"]]
             :items   [[{} [entity.ui/persisted-attr project :project/sticky? (assoc field-params :field/label "Sticky?")]]]}])

         [radix/tooltip "Back to board"
          [:a.modal-title-icon {:href (routing/entity-path (:entity/parent project) 'ui/show)}
           [icons/arrow-left]]]
         (when (:entity/id project)
           [radix/tooltip "Link to project"
            [:a.modal-title-icon {:href (routing/entity-path project :show)}
             [icons/link-2]]])
         [radix/dialog-close
          [:div.modal-title-icon [icons/close]]]]]]

      [:div.px-body.flex-v.gap-6

       [entity.ui/persisted-attr project :project/badges field-params]
       [entity.ui/persisted-attr project :entity/description (merge field-params
                                                                    {:field/label false
                                                                     :placeholder "Description"})]
       [entity.ui/persisted-attr project :entity/video field-params]

       [entity.ui/persisted-attr project
        :entity/field-entries
        {:entity/fields    (->> project :entity/parent :entity/project-fields)
         :membership/roles roles
         :field/can-edit?  can-edit?}]

       [project-members project field-params]

       #_[:section.flex-v.gap-2.items-start
          [manage-community-actions project (:project/community-actions project)]]]]]))

(defn membership-colors [membership]
  (into []
        (map #(->> (:tag/id %) (resolve-tag (:membership/entity membership)) (:tag/color)))
        (:entity/tags membership)))

(ui/defview card
  {:key :entity/id}
  [{:keys [entity/project-fields]}
   {:as   entity
    :keys [entity/parent
           entity/title
           entity/description
           entity/field-entries
           project/sticky?
           membership/roles]}]
  (let [board-members (->> (:membership/_entity entity) (mapv :membership/member))]
    [:a.flex-v.hover:bg-gray-100.rounded-lg.bg-slate-100.py-3.gap-3.
     (v/merge-props {:href (routing/entity-path entity 'ui/show)}
                    (when sticky?
                      {:class "outline outline-4"
                       :style {:outline-color (-> entity :entity/parent :board/sticky-color)}}))

     [:div.flex.relative.gap-3.items-start.px-3.cursor-default.flex-auto
      [:div.flex-grow.flex-v.gap-1
       [:div.leading-snug.line-clamp-2.font-semibold.text-lg title]
       [:div.text-gray-500 description]
       [:div.text-gray-500.contents
        (field.ui/show-entries project-fields field-entries)]]]

     ;; TEAM
     [:div.flex.flex-wrap.gap-2.px-3
      (u/for! [board-member (take 6 board-members)
               :let [account (:membership/member board-member)]]
        [:div.w-10.flex-v.gap-1
         [ui/avatar {:size 10} account]
         [:div.flex.h-2.items-stretch.rounded-sm.overflow-hidden
          (for [color (membership-colors board-member)]
            [:div.flex-auto {:style {:background-color color}}])]])
      (when-let [more (-> (- (count board-members) 6)
                          (u/guard pos-int?))]
        [:div.w-10.h-10.flex-center.text-gray-400.text-lg.tracking-wider
         [:div.flex  "+" more]])]]))