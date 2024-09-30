(ns sb.app.membership.ui
  (:require [re-db.api :as db]
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
            [sb.util :as u]))

(ui/defview show
  {:route       "/m/:membership-id"
   :view/router :router/modal}
  [params]
  (let [{:as     membership
         account :membership/member} (data/show params)
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
        [entity.ui/persisted-attr membership :entity/tags field-params]]
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
         [:a.modal-title-icon {:href (routing/entity-path (:membership/entity membership) 'ui/show)}
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
       {:entity/fields    (->> membership :membership/entity :entity/member-fields)
        :membership/roles roles
        :field/can-edit?  can-edit?}]]
     [:div.px-body
      [:div.field-label (t :tr/project)]
      [:div.mt-3.flex.flex-wrap.gap-6
       (for [project (->> (db/where [[:membership/member (sch/wrap-id account)]])
                          (map :membership/entity)
                          (filter (every-pred (comp #{(:membership/entity membership)} :entity/parent)
                                              ;; filter out sticky notes. TODO do we want to show them somewhere else?
                                              (comp #{:project} :entity/kind))))]
         ^{:key (:entity/id project)}
         [:a.btn.btn-white {:href (routing/entity-path project 'ui/show)}
          (:entity/title project)])]]]))

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

;; TODO review membership pointing at membership
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
