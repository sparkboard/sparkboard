(ns sb.app.membership.ui
  (:require [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.membership.data :as data]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.authorize :as az]
            [sb.color :as color]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.schema :as sch]))

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
        [:h1.font-medium.text-2xl.flex-auto.flex.items-center.mt-2 (-> membership :membership/member :account/display-name)]
        [entity.ui/persisted-attr membership :entity/tags field-params]]
       [:a.btn.btn-white.flex.items-center.px-3.my-auto
        {:href (routing/path-for ['sb.app.chat.ui/chat {:other-id (:membership-id params)}])}
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
        [radix/dialog-close
         [:div.modal-title-icon [icons/close]]]]]]
     [:div.px-body.flex-v.gap-6
      [entity.ui/persisted-attr membership
       :entity/field-entries
       {:entity/fields    (->> membership :membership/entity :entity/member-fields)
        :membership/roles roles
        :field/can-edit?  can-edit?}]]]))

(defn show-tag [{:keys [tag/label tag/color] :or {color "#dddddd"}}]
  [:div.tag-md
   {:key   label
    :style {:background-color color
            :color            (color/contrasting-text-color color)}}
   label])

(ui/defview card
  {:key (fn [_ member] (str (:entity/id member)))}
  [{:keys [entity/member-fields
           entity/member-tags]} board-member]
  (let [{:keys [entity/field-entries
                entity/tags
                entity/custom-tags]} board-member
        {:as account :keys [account/display-name]} (:membership/member board-member)]
    [:a.flex-v.hover:bg-gray-100.rounded-lg.bg-slate-100 #_.rounded-xl.border.shadow
     {:href (routing/entity-path board-member 'ui/show)}
     [:div.flex.relative.gap-3.items-start.p-3.cursor-default.flex-auto
      [ui/avatar {:size 10} account]

      [:div.flex-grow.flex-v.gap-1
       [:div.leading-snug.line-clamp-2.font-semibold display-name]
       [:div.flex.flex-wrap.gap-1
        (->> member-tags
             (filter (comp (into #{} (map :tag/id) tags) :tag/id))
             (map show-tag))
        [:div.flex.flex-wrap.gap-1 (map show-tag custom-tags)]]
       [:div.text-gray-500.contents (field.ui/show-entries member-fields field-entries)]]]]))
