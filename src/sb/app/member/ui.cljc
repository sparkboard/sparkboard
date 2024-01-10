(ns sb.app.member.ui
  (:require [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.member.data :as data]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.app.views.ui :as ui]
            [sb.authorize :as az]
            [sb.color :as color]
            [sb.icons :as icons]
            [sb.routing :as routing]))

(ui/defview show
  {:route       "/m/:member-id"
   :view/router :router/modal}
  [params]
  (let [{:as member :keys [member/account]} (data/show params)
        [can-edit? roles dev-panel] (form.ui/use-dev-panel member {"Current User" (az/all-roles (:account-id params) member)
                                                                   "Board Admin"  #{:role/board-admin}
                                                                   "Visitor"      #{}}
                                                           "Current User")
        field-params {:member/roles    roles
                      :field/can-edit? can-edit?}]
    [:div.flex-v.gap-6.py-6
     ;; title row
     [:div.flex-v
      [:div.flex.px-6.gap-3
       [ui/avatar {:size 18} account]
       [:div.flex-v.gap-2
        [:h1.font-medium.text-2xl.flex-auto.flex.items-center.mt-6 (-> member :member/account :account/display-name)]
        (entity.ui/use-persisted-attr member :entity/tags field-params)]

       [:div.flex.px-1.rounded-bl-lg.border-b.border-l.absolute.top-0.right-0
        dev-panel
        (comment
          (when (:role/board-admin roles)
            ;; - modify role
            [radix/dropdown-menu
             {:trigger [:div.flex.items-center [icons/ellipsis-horizontal "rotate-90 icon-gray"]]
              :items   []}]))

        [radix/tooltip "Back to board"
         [:a.modal-title-icon {:href (routing/entity-path (:member/entity member) 'ui/show)}
          [icons/arrow-left]]]
        [radix/tooltip "Link to member"
         [:a.modal-title-icon {:href (routing/entity-path member 'ui/show)}
          [icons/link-2]]]
        [radix/dialog-close
         [:div.modal-title-icon [icons/close]]]]]]
     [:div.px-body.flex-v.gap-6
      (entity.ui/use-persisted-attr member
                                    :entity/field-entries
                                    {:entity/fields   (->> member :member/entity :entity/member-fields)
                                     :member/roles    roles
                                     :field/can-edit? can-edit?})]]))

(defn show-tag [{:keys [tag/label tag/color] :or {color "#dddddd"}}]
  [:div.tag-sm
   {:key   label
    :style {:background-color color
            :color            (color/contrasting-text-color color)}}
   label])

(ui/defview card
  {:key (fn [_ member] (str (:entity/id member)))}
  [{:keys [entity/member-fields]} member]
  (let [{:keys [entity/field-entries
                entity/tags
                entity/custom-tags
                member/account]} member
        {:keys [account/display-name]} account]
    [:a.flex-v.hover:bg-gray-100.rounded-lg
     {:href (routing/entity-path member 'ui/show)}


     [:div.flex.relative.gap-3.items-center.p-2.cursor-default.flex-auto
      [ui/avatar {:size 10} account]
      [:div.line-clamp-2.leading-snug.flex-grow.flex-v.gap-1 display-name
       [:div.flex.flex-wrap.gap-1
        (map show-tag tags)
        (map show-tag custom-tags)]]]
     ;; show card entries on hover?
     #_(when-let [entries (seq
                            (for [{:as field :keys [field/id
                                                    field/label]} member-fields
                                  :let [entry (get field-entries id)]
                                  :when entry]
                              (assoc entry :field-entry/field field)))]
         [:div.text-gray-500.ml-14.pl-1
          (map field.ui/show-entry:card entries)])]))
