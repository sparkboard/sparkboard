(ns sb.app.org.ui
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :refer [read-string]]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.domain-name.ui :as domain.ui]
            [sb.app.entity.data :as entity.data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.membership.data :as member.data]
            [sb.app.org.data :as data]
            [sb.app.views.header :as header]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.authorize :as az]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(ui/defview show
  {:route "/o/:org-id"}
  [params]
  (forms/with-form [_ [?filter (?sort :init [:default])]]
    (let [{:as   org
           :keys [entity.data/description]} (data/show params)
          q     (ui/use-debounced-value (u/guard @?filter #(> (count %) 2)) 500)
          [result set-result!] (h/use-state nil)
          title (v/from-element :h3.font-medium.text-lg.p-2)]
      (h/use-effect
        (fn []
          (when q
            (let [q q]
              (set-result! {:loading? true})
              (p/let [result (data/search-once {:org-id (:org-id params)
                                                :q      q})]
                (when (= q @?filter)
                  (set-result! {:value result
                                :q     q}))))))
        [q])
      [:div
       {:style (ui/background-image-style org)}
       (header/entity org nil)
       (ui/sub-header org)
       ;; TODO where does org description actually come from?
       [:div.p-body (field.ui/show-prose description)]
       [:div.p-body
        [:div.flex.gap-4.items-stretch.backdrop-blur-md.p-2.rounded-lg
         {:class "bg-white/20"}
         ;; TODO filter field is not as tall as sort selection and new-project button, looks ugly
         [field.ui/filter-field ?filter {:loading? (:loading? result)}]
         [field.ui/select-field ?sort
          {:field/classes {:wrapper "flex-row items-center"}
           :field/label (t :tr/sort-order)
           :field/can-edit? true
           :field/wrap read-string
           :field/unwrap str
           :field/options [{:field-option/value [:default]
                            :field-option/label (t :tr/sort-default)}
                           {:field-option/value [:entity/created-at :direction :asc]
                            :field-option/label (t :tr/sort-entity-created-at-asc)}
                           {:field-option/value [:entity/created-at :direction :desc]
                            :field-option/label (t :tr/sort-entity-created-at-desc)}
                           {:field-option/value [:random]
                            :field-option/label (t :tr/sort-random)}]}]
         [:div
          [:a.btn.btn-white.py-2
           {:class "bg-white/40"
            :href (routing/path-for ['sb.app.board.ui/new-in-org
                                     {:parent (:entity/id org)}])}
           (t :tr/new-board)]]
         [:div
          [:a.btn.btn-white.py-2
           {:class "bg-white/40"
            :href (routing/path-for `members {:org-id (:org-id params)})}
           (t :tr/members)]]
         (when-let [join! (data/approve-membership!-authorized {:org-id (:org-id params)})]
           [ui/action-button {:class "bg-white/40"
                              :on-click (fn [_] (join!))}
            (t :tr/join)])]
        [ui/error-view result]
        (for [[kind results] (if (seq q)
                               (dissoc (:value result) :q)
                               [[:boards (:entity/_parent org)]])
              :when (seq results)]
          [:div.flex-v.gap-2.my-6.p-1.backdrop-blur-md.rounded-lg
           {:class "bg-white/20"}
           [title (t (keyword "tr" (name kind)))]
           (into  [:div.grid.grid-cols-1.sm:grid-cols-2.md:grid-cols-3.lg:grid-cols-4.gap-2]
                  (comp (apply ui/sorted @?sort)
                        (map entity.ui/row))
                  results)])]])))

(ui/defview avatar-name [{:keys [on-click]}
                         {:as account :keys [account/display-name]}]
  [:div.flex.items-center.gap-2
   {:key      (:entity/id account)
    :on-click on-click}
   [ui/avatar {:size 12} account]
   [:div display-name]])

(ui/defview member-with-admin [org-membership]
  (let [account (:membership/member org-membership)]
    [:div.flex-v
     [avatar-name {:on-click #(routing/nav! (routing/entity-route account 'ui/show))}
      account]
     (when (entity.data/save-attributes!-authorized {:entity {:entity/id (:entity/id org-membership)
                                                              :membership/roles #{:role/org-admin}}})
       (into [:div.flex.flex-wrap.gap-2]
             (map (fn [role]
                    [:label.flex.items-center.gap-1
                     [:input {:type "checkbox"
                              :checked (boolean (role (:membership/roles org-membership)))
                              :on-change (fn [event]
                                           (entity.data/save-attributes!
                                            {:entity {:entity/id (:entity/id org-membership)
                                                      :membership/roles ((if (-> event .-target .-checked)
                                                                           (fnil conj #{})
                                                                           disj)
                                                                         (:membership/roles org-membership)
                                                                         role)}}))}]
                     [:div
                      (t (keyword "tr" (name role)))]]))
             [:role/org-admin]))
     (when-let [delete! (entity.data/delete!-authorized {:entity-id (:entity/id org-membership)})]
       [ui/action-button
        {:class "bg-white h-8"
         :on-click (fn [_]
                     (delete!))}
        (t :tr/remove-from-org)])]))

(ui/defview invitation-list [entity ?user-filter]
  (when-let [user-filter @?user-filter]
    [:<>
     #_[ui/pprinted (mapv deref (data/search-users {:filter-term user-filter}))]
     (into [:div.flex-v.gap-1]
           (map (fn [account]
                  ;; TODO make it more obvious in the UI that clicking will invite
                  [avatar-name {:on-click #(do
                                             (reset! ?user-filter nil)
                                             (data/create-invitation!
                                              {:invitee-account-id (:entity/id account)
                                               :entity-id (:entity/id entity)}))}
                   account]))
             (data/search-users {:filter-term user-filter}))]))

(ui/defview invitation-widget [entity]
  (let [?user-filter @(h/use-state (forms/field))]
    [:<>
     [field.ui/filter-field ?user-filter {:placeholder (t :tr/search-to-invite)}]
     [:Suspense {}
      [invitation-list entity ?user-filter]]]))

(ui/defview members
  {:route "/o/:org-id/members"
   :view/router :router/modal}
  [params]
  (let [members (data/members {:org-id (:org-id params)})]
    [:div.p-body.flex-v.gap-3
     [:div.flex.gap-3
      [:h1.font-medium.text-2xl (t :tr/members)]
      [:div.flex.px-1.rounded-bl-lg.border-b.border-l.absolute.top-0.right-0
       [radix/dialog-close
        [:div.modal-title-icon [icons/close]]]]]
     (into [:div.grid.grid-cols-2.gap-6]
           (map member-with-admin)
           members)
     (when (az/admin-role? (az/all-roles (:account-id params) (db/entity (:org-id params))))
       [:<>
        (when-let [memberships (seq (data/pending-memberships {:org-id (:org-id params)}))]
          [:div.field-wrapper
           [:div.field-label (t :tr/pending)]
           (into [:div.grid.grid-cols-2.gap-6]
                 (map member-with-admin)
                 memberships)])
        [invitation-widget (db/entity (:org-id params))]])]))

(ui/defview new
  {:route       "/new/o"
   :view/router :router/modal}
  [params]
  (forms/with-form [!org (u/prune
                           {:entity/title ?title})
                    :required [?title]]
    [:form
     {:class     form.ui/form-classes
      :on-submit (fn [e]
                   (.preventDefault e)
                   (ui/with-submission [result (data/new! {:org @!org})
                                        :form !org]
                     (routing/nav! [`show {:org-id (:entity/id result)}])))}
     [:h2.text-2xl (t :tr/new-org)]
     [field.ui/text-field ?title {:field/label (t :tr/title)
                                  :field/can-edit? true}]
     [form.ui/submit-form !org (t :tr/create)]]))
