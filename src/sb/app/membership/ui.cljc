(ns sb.app.membership.ui
  (:require [promesa.core :as p]
            [inside-out.forms :as io]
            [re-db.api :as db]
            [sb.app.board.data :as board.data]
            [sb.app.entity.data :as entity.data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.membership.data :as data]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.authorize :as az]
            [sb.color :as color]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [sb.util :as u]
            [yawn.hooks :as h]
            [net.cgrand.xforms :as xf]))

;; :accounts can have a :membership in an :org, :board, :project, or :note
;; The following table shows the differences between the different membership types
;;                                       O B P N
;; can have tags                         - o - -
;; needs to be also member of the parent - - o o

(ui/defview show
  {:route       "/m/:membership-id"
   :view/router :router/modal}
  [params]
  (let [{:as     membership
         account :membership/member
         board   :membership/entity} (data/show params)
        [can-edit? roles dev-panel] (form.ui/use-dev-panel membership {"Current User" (az/all-roles (:account-id params) membership)
                                                                       "Board Admin"  #{:role/board-admin}
                                                                       "This User"    #{:role/self}
                                                                       "Visitor"      #{}}
                                                           "Current User")
        field-params {:membership/roles roles
                      :field/can-edit?  can-edit?}]
    [:div.flex-v.gap-6.py-6
     ;; title row
     [:div.flex-v
      [:div.flex.px-6.gap-3
       (when (:image/avatar account) [ui/avatar {:size 20} account])
       [:div.flex-v.gap-2.grow
        [:h1.font-medium.text-2xl.flex-auto.flex.items-center.mt-2 (:account/display-name account)]
        [entity.ui/persisted-attr membership :entity/tags field-params]
        [entity.ui/persisted-attr membership :entity/custom-tags field-params]]
       [:a.btn.btn-white.flex.items-center.px-3.my-auto
        {:href (routing/path-for ['sb.app.chat.ui/chat {:other-id (sch/wrap-id account)}])}
        "message"]

       [:div.flex.px-1.rounded-bl-lg.border-b.border-l.absolute.top-0.right-0
        dev-panel
        (comment
          (when (:role/board-admin roles)
            ;; - modify role
            [radix/dropdown-menu
             {:trigger [:div.flex.items-center [icons/ellipsis-horizontal "rotate-90 icon-gray"]]
              :items   []}]))

        [radix/tooltip "Back to board"
         [:a.modal-title-icon {:href (routing/entity-path board 'ui/show)}
          [icons/arrow-left]]]
        [radix/tooltip "Link to member"
         [:a.modal-title-icon {:href (routing/entity-path membership 'ui/show)}
          [icons/link-2]]]
        [radix/tooltip "View user"
         [:a.modal-title-icon {:href (routing/entity-path account 'ui/show)}
          "U"]]
        [radix/dialog-close
         [:div.modal-title-icon [icons/close]]]]]]
     [:div.px-body.flex-v.gap-6
      [entity.ui/persisted-attr membership
       :entity/field-entries
       {:entity/fields    (-> board :entity/member-fields)
        :membership/roles roles
        :field/can-edit?  can-edit?}]]
     (let [[invitations theirs] (->> (data/project-memberships-on-board board (sch/wrap-id account))
                                     (u/filter-by :membership/member-approval-pending?)
                                     (map (partial mapv :membership/entity)))
           mine (->> (data/project-memberships-on-board board (:account-id params))
                     (map :membership/entity)
                     (filter (comp (complement (into (set theirs) invitations)))))]
       [:div.px-body
        ;; TODO do we want to show sticky-note memberships somewhere?
        [:div.field-label (t :tr/project)]
        [:div.flex-v.gap-6
         (when (seq theirs)
           [:div.mt-3.flex.flex-wrap.gap-6
            (for [project theirs]
              ^{:key (:entity/id project)}
              [:a.btn.btn-white {:href (routing/entity-path project 'ui/show)}
               (:entity/title project)])])
         (when (seq invitations)
           [:div
            [:div.field-label.text-sm (t :tr/invited-to)]
            [:div.mt-3.flex.flex-wrap.gap-6
             (for [project invitations]
               ^{:key (:entity/id project)}
               [:a.btn.btn-white {:href (routing/entity-path project 'ui/show)}
                (:entity/title project)])]])
         (when (seq mine)
           [:div
            [:div.field-label.text-sm (t :tr/invite)]
            [:div.mt-3.flex.flex-wrap.gap-6
             (for [project mine]
               ^{:key (:entity/id project)}
               ;; TODO do we want to enable invitation to sticky notes here?
               [ui/action-button {:classes {:btn "btn-primary"}
                                  :on-click #(data/create-board-child-invitation!
                                              {:invitee-account-id (:entity/id account)
                                               :entity-id (:entity/id project)})}
                (t :tr/invite-to [(:entity/title project)])])]])]])
     (when (entity.data/save-attributes!-authorized {:entity {:entity/id (:entity/id membership)
                                                              :membership/roles #{:role/board-admin}}})
       [:div.px-body
        [:div.field-label
         (t :tr/roles)]
        [:label.flex.items-center.gap-1
         [:input {:type "checkbox"
                  :checked (boolean (:role/board-admin (:membership/roles membership)))
                  :on-change (fn [event]
                               (entity.data/save-attributes!
                                {:entity {:entity/id (:entity/id membership)
                                          :membership/roles ((if (-> event .-target .-checked)
                                                               (fnil conj #{})
                                                               disj)
                                                             (:membership/roles membership)
                                                             :role/board-admin)}}))}]
         [:div
          (t :tr/board-admin)]]])
     (if (and (:membership/member-approval-pending? membership)
              (sch/id= (:account-id params) account))
       [:div.px-body.flex-v.gap-2
        (t :tr/join-blurb)
        [ui/action-button
         (if-let [approve! (data/approve-board-membership!-authorized {:board-id (:entity/id board)})]
           {:class "bg-white"
            :on-click (fn [_]
                        (p/let [result (data/approve-board-membership! {:board-id (:entity/id board)})]
                          (when-not (:error result)
                            (routing/dissoc-router! :router/modal))
                          result))}
           {:disabled true})
         (t :tr/join)]]
       (when-let [delete! (entity.data/delete!-authorized {:entity-id (:entity/id membership)})]
         [:div.px-body
          [ui/action-button
           {:class "bg-white"
            :on-click (fn [_]
                        (p/do
                          (delete!)
                          (routing/dissoc-router! :router/modal)))}
           (if (:role/self roles)
             (t :tr/leave-board)
             (t :tr/remove-from-board))]]))]))

(ui/defview tags [size board-membership]
  (let [tag-class (case size :small "tag-sm" :medium "tag-md")]
    (into [:div.flex.flex-wrap.gap-1]
          (map (fn [{:tag/keys [id label color] :or {color "#dddddd"}}]
                 [:div
                  {:class tag-class
                   :style (color/color-pair color)}
                  label]))
          (concat (data/resolved-tags board-membership)
                  (:entity/custom-tags board-membership)))))

(ui/defview card
  {:key (fn [_ member] (str (:entity/id member)))}
  [{:keys [entity/member-fields]} board-member]
  (let [{:keys [entity/field-entries]} board-member
        {:as account :keys [account/display-name]} (:membership/member board-member)]
    [:a.flex-v.hover:bg-gray-100.rounded-lg.bg-slate-100 #_.rounded-xl.border.shadow
     {:href (routing/entity-path board-member 'ui/show)}
     [:div.flex.relative.gap-3.items-start.p-3.cursor-default.flex-auto
      [ui/avatar {:size 10} account]

      [:div.flex-grow.flex-v.gap-1
       [:div.leading-snug.line-clamp-2.font-semibold display-name]
       [tags :medium board-member]
       [:div.text-gray-500.contents (field.ui/show-entries member-fields field-entries)]]]]))

(ui/defview members-for-card [entity]
  (let [members (data/members entity)]
    [:div.flex.flex-wrap.gap-2.px-3
     (u/for! [account (take 6 members)
              :let [member (az/membership account (:entity/parent entity))]]
       [:div.w-10.flex-v.gap-1
        [ui/avatar {:size 10} account]
        [:div.flex.h-2.items-stretch.rounded-sm.overflow-hidden
         (for [color (data/membership-colors member)]
           [:div.flex-auto {:style {:background-color color}}])]])
     (when-let [more (-> (- (count members) 6)
                         (u/guard pos-int?))]
       [:div.w-10.h-10.flex-center.text-gray-400.text-lg.tracking-wider
        [:div.flex  "+" more]])]))

(ui/defview avatar-name-tags [{:keys [on-click]}
                 board-membership
                 {:as account :keys [account/display-name]}]
  [:div.flex.items-center.gap-2
   {:key      (:entity/id account)
    :on-click on-click}
   [ui/avatar {:size 12} account]
   [:div.flex-v.gap-1
    display-name
    [tags :small board-membership]]])

(ui/defview with-admin [entity props entity-membership]
  (let [account (:membership/member entity-membership)
        board-membership (az/membership account (:entity/parent entity))]
    [:div.flex-v.gap-1.cursor-default.hover:bg-gray-100.py-3.rounded
     [avatar-name-tags {:on-click #(routing/nav! (routing/entity-route board-membership 'ui/show))}
      board-membership
      account]
     (when-let [possible-roles (:possible-roles props)]
       (when (entity.data/save-attributes!-authorized {:entity {:entity/id (:entity/id entity-membership)
                                                                :membership/roles (set possible-roles)}})
         (into [:div.flex.flex-wrap.gap-2]
               (map (fn [role]
                      [:label.flex.items-center.gap-1
                       [:input {:type "checkbox"
                                :checked (boolean (role (:membership/roles entity-membership)))
                                :on-change (fn [event]
                                             (entity.data/save-attributes!
                                              {:entity {:entity/id (:entity/id entity-membership)
                                                        :membership/roles ((if (-> event .-target .-checked)
                                                                             (fnil conj #{})
                                                                             disj)
                                                                           (:membership/roles entity-membership)
                                                                           role)}}))}]
                       [:div
                        (t (keyword "tr" (name role)))]]))
               possible-roles)))
     (when-let [delete! (entity.data/delete!-authorized {:entity-id (:entity/id entity-membership)})]
       [ui/action-button
        {:class "bg-white h-8"
         :on-click (fn [_]
                     (delete!))}
        (t :tr/remove)])]))

(ui/defview invitation-list [entity ?user-filter]
  (when-let [user-filter @?user-filter]
    (into [:div.flex-v.gap-1]
          (comp (filter (ui/match-pred user-filter))
                (take 10)
                (map (fn [board-membership]
                       (let [account (:membership/member board-membership)]
                         ;; TODO make it more obvious in the UI that clicking will invite
                         [avatar-name-tags {:on-click #(do
                                                         (reset! ?user-filter nil)
                                                         (data/create-board-child-invitation!
                                                          {:invitee-account-id (:entity/id account)
                                                           :entity-id (:entity/id entity)}))}
                          board-membership
                          account]))))
          (board.data/members {:board-id (sch/wrap-id (:entity/parent entity))}))))

(ui/defview invitation-widget [entity]
  (let [?user-filter @(h/use-state (io/field))]
    [:<>
     [field.ui/filter-field ?user-filter {:placeholder (t :tr/search-to-invite)}]
     [:Suspense {}
      [invitation-list entity ?user-filter]]]))

(ui/defview for-modal [entity props]
  ;; todo
  ;; 4. hover to see member details
  [:<>
   (when (az/admin-role? (:membership/roles props))
     ;; TODO ensure :role/project-editor can't change this on the server
     [entity.ui/persisted-attr entity :entity/admission-policy props])
   (when-let [memberships (seq (data/memberships entity (xf/sort-by :entity/created-at u/compare:desc)))]
     [:div.field-wrapper
      [:div.field-label (t :tr/team)]
      (into [:div.grid.grid-cols-2.gap-x-6]
            (map (partial with-admin entity props))
            memberships)])
   (when (az/admin-role? (:membership/roles props))
     [:<>
      (when-let [memberships (seq (data/pending-memberships entity (xf/sort-by :entity/created-at u/compare:desc)))]
        [:div.field-wrapper
         [:div.field-label (t :tr/pending)]
         (into [:div.grid.grid-cols-2.gap-x-6]
               (map (partial with-admin entity props))
               memberships)])
      [invitation-widget entity]])
   (if-let [membership (some-> (db/get :env/config :account)
                               (az/membership entity)
                               not-empty)]
     (if (:membership/member-approval-pending? membership)
       [:<>
        (t :tr/you-are-invited-to-join)
        [ui/action-button
         {:on-click (fn [_] (data/approve-membership! {:entity-id (:entity/id entity)}))}
         (t :tr/join)]]
       (when-let [delete! (entity.data/delete!-authorized {:entity-id (:entity/id membership)})]
         [ui/action-button
          {:class "bg-white"
           :on-click (fn [_]
                       (p/let [result (delete!)]
                         (routing/dissoc-router! :router/modal)
                         result))}
          (t :tr/leave)]))
     (when-let [join! (data/join-board-child!-authorized {:entity-id (sch/unwrap-id entity)})]
       [ui/action-button
        {:on-click (fn [_] (join!))}
        (t :tr/join)]))])
