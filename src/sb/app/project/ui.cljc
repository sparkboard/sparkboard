(ns sb.app.project.ui
  (:require [inside-out.forms :as forms]
            [promesa.core :as p]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.board.data :as board.data]
            [sb.app.entity.data :as entity.data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.discussion.ui :as discussion.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.membership.ui :as member.ui]
            [sb.app.project.data :as data]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.app.vote.data :as vote.data]
            [sb.authorize :as az]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [re-db.api :as db]
            [net.cgrand.xforms :as xf]))

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


(ui/defview project-members [project props]
  ;; todo
  ;; 1. pass in all-roles to know if we are an a board-admin
  ;; 3. button for adding a new member via searching this board
  ;; 4. hover to see member details
  ;; 5. fix chat
  [:div.field-wrapper
   [:div.field-label (t :tr/team)]
   [:div.grid.grid-cols-2.gap-6
    (for [board-membership (member.data/members project (xf/sort-by :entity/created-at u/compare:desc))
          :let [{:as account :keys [account/display-name]} (-> board-membership :membership/member)]]
      [:div.flex.items-center.gap-2
       {:key      (:entity/id board-membership)
        :on-click #(routing/nav! (routing/entity-route board-membership 'ui/show))}
       [ui/avatar {:size 12} account]
       [:div.flex-v.gap-1
        display-name
        [member.ui/tags :small board-membership]]])]
   (when ((some-fn :role/project-admin :role/board-admin) (:membership/roles props))
     [entity.ui/persisted-attr project :entity/admission-policy props])
   (if (some-> (db/get :env/config :account)
               (az/membership-id (:entity/parent project))
               (az/membership-id project))
     [ui/action-button
      {:on-click (fn [_] (data/leave! {:project-id (sch/unwrap-id project)}))}
      "leave"]
     (when (= :admission-policy/open (:entity/admission-policy project))
       [ui/action-button
        {:on-click (fn [_] (data/join! {:project-id (sch/unwrap-id project)}))}
        "join"]))])

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
    ;; We cannot hoist this `if` above the call to `use-dev-panel` as that makes react mad about hooks
    (if project
      [:<>
       (when (:entity/draft? project)
         [:div.border-b-2.border-dashed.px-body.py-2.flex-center.gap-3.bg-gray-100
          [:div.mr-auto.text-gray-500 "Draft - only visible to you."]
          [ui/action-button {:on-click #(entity.data/save-attribute! nil (:entity/id project) :entity/draft? false)
                             :classes  {:btn          "btn-primary px-4 py-1"
                                        :progress-bar "text-[rgba(255,255,255,0.5)]"}}
           (t :tr/publish)]])
       [:div.flex-v.gap-6.pb-6.rounded-lg.relative
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
               :items   []}])

           [radix/tooltip "Back to board"
            [:a.modal-title-icon {:href (routing/entity-path (:entity/parent project) 'ui/show)}
             [icons/arrow-left]]]
           (when (:entity/id project)
             [radix/tooltip "Link to project"
              [:a.modal-title-icon {:href (routing/entity-path project 'ui/show)}
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
            [manage-community-actions project (:project/community-actions project)]]

         [entity.ui/persisted-attr project :project/open-requests field-params]

         [:div.field-label (t :tr/questions-and-comments)]
         [discussion.ui/show-posts project]

         (when can-edit?
           [ui/action-button {:on-click (fn [_]
                                          (p/let [result (data/delete! nil {:project-id (sch/unwrap-id (:project-id params))})]
                                            (routing/dissoc-router! :router/modal)
                                            result))}
            "delete"])]]]
      [ui/error-view
       {:error "Project not found"}])))

(ui/defview card
  {:key #(:entity/id %2)}
  [{:keys [entity/project-fields]}
   {:as   entity
    :keys [entity/parent
           entity/title
           entity/description
           entity/field-entries
           project/open-requests
           membership/roles]}]
  [:a.flex-v.hover:bg-gray-100.rounded-lg.bg-slate-100.py-3.gap-3.
   {:href (routing/entity-path entity 'ui/show)}

   [:div.flex.relative.gap-3.items-start.px-3.cursor-default.flex-auto
    [:div.flex-grow.flex-v.gap-1
     [:div.leading-snug.line-clamp-2.font-semibold.text-lg title]
     [:div.text-gray-500
      (field.ui/show-prose description)]
     [:div.text-gray-500.contents
      (field.ui/show-entries project-fields field-entries)]]]

   [:div.ml-4
    [field.ui/show-requests open-requests]]

   ;; TEAM
   [member.ui/members-for-card entity]])

(ui/defview vote-card
  {:key :entity/id}
  [{:as   entity
    :keys [entity/title
           project/number]}]
  [:div.flex-v.hover:bg-gray-100.rounded-lg.bg-slate-100.py-3.gap-3.
   {:class (when (sch/id= entity
                          (-> (board.data/user-ballot {:board-id (sch/wrap-id (:entity/parent entity))})
                              :ballot/project))
             "outline outline-green-500")}
   [:div.flex.relative.gap-3.items-start.px-3.cursor-default.flex-auto
    [:div.flex-grow.flex-v.gap-1
     [:a.flex
      {:href (routing/entity-path entity 'ui/show)}
      [:div.leading-snug.line-clamp-2.font-semibold.text-lg.flex-grow.text-center title]
      [:div.text-gray-500.font-bold (some->> number (str "#"))]]
     [:button.btn.btn-white.h-10
      {:on-click #(vote.data/new! {:project-id (sch/wrap-id entity)})}
      (t :tr/vote)]]]])
