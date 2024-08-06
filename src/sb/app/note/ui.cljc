(ns sb.app.note.ui
  (:require [promesa.core :as p]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.entity.data :as entity.data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.membership.data :as member.data]
            [sb.app.membership.ui :as member.ui]
            [sb.app.note.data :as data]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.color :as color]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [sb.util :as u]
            [net.cgrand.xforms :as xf]))

;; TODO taken from project.ui/project-members check if can be merged
(ui/defview note-members [note props]
  ;; todo
  ;; 3. button for adding a new member via searching this board
  ;; 4. hover to see member details
  ;; 5. fix chat
  [:div.field-wrapper
   [:div.field-label (t :tr/members)]
   [:div.grid.grid-cols-2.gap-6
    (for [board-membership (member.data/members note (xf/sort-by :entity/created-at u/compare:desc))
          :let [{:as account :keys [account/display-name]} (-> board-membership :membership/member)]]
      [:div.flex.items-center.gap-2
       {:key      (:entity/id board-membership)
        :on-click #(routing/nav! (routing/entity-route board-membership 'ui/show))}
       [:img.object-cover.rounded.w-12.h-12 {:src (asset.ui/asset-src (:image/avatar account) :avatar)}]
       [:div.flex-v.gap-1
        display-name
        [:div.flex.flex-wrap.gap-2
         (for [{:as tag :tag/keys [id label color]} (member.data/resolved-tags board-membership)]
           [:div.tag-sm {:style (color/color-pair color)}
            label])]]])]])

(ui/defview show
  {:route       "/n/:note-id"
   :view/router :router/modal}
  [params]
  (let [{board :entity/parent :as note} (data/show params)
        [can-edit? roles dev-panel] (form.ui/use-dev-panel note {"Current User"   (:membership/roles board)
                                                                 "Board Admin"    #{:role/board-admin}
                                                                 "Visitor"        #{}} "Current User")
        field-params {:membership/roles roles
                      :field/can-edit?  can-edit?}]
    ;; We cannot hoist this `if` above the call to `use-dev-panel` as that makes react mad about hooks
    (if note
      [:<>
       (when (:entity/draft? note)
         [:div.border-b-2.border-dashed.px-body.py-2.flex-center.gap-3.bg-gray-100
          [:div.mr-auto.text-gray-500 "Draft - only visible to you."]
          [field.ui/action-btn {:on-click #(entity.data/save-attribute! nil (:entity/id note) :entity/draft? false)
                                :classes  {:btn          "btn-primary px-4 py-1"
                                           :progress-bar "text-[rgba(255,255,255,0.5)]"}}
           (t :tr/publish)]])
       [:div.flex-v.gap-6.pb-6.rounded-lg.relative
        {:class "outline outline-4"
         :style {:outline-color (:note/outline-color note)
                 :margin        4}}

        ;; title row
        [:div.flex-v.mt-6
         [:div.flex
          [:h1.font-medium.text-2xl.flex-auto.px-body.flex.items-center.pt-6
           [entity.ui/persisted-attr note :entity/title (merge field-params
                                                               {:field/label       false
                                                                :field/multi-line? false
                                                                :field/unstyled?   (some-> (:entity/title note)
                                                                                           (not= "Untitled"))})]]


          [:div.flex.px-1.rounded-bl-lg.border-b.border-l.absolute.top-0.right-0
           dev-panel

           [radix/tooltip "Back to board"
            [:a.modal-title-icon {:href (routing/entity-path (:entity/parent note) 'ui/show)}
             [icons/arrow-left]]]
           (when (:entity/id note)
             [radix/tooltip "Link to sticky note"
              [:a.modal-title-icon {:href (routing/entity-path note 'ui/show)}
               [icons/link-2]]])
           [radix/dialog-close
            [:div.modal-title-icon [icons/close]]]]]]

        [:div.px-body.flex-v.gap-6
         (when can-edit?
           [entity.ui/persisted-attr note :note/outline-color field-params])

         [entity.ui/persisted-attr note :note/badges field-params]
         [entity.ui/persisted-attr note :entity/description (merge field-params
                                                                   {:field/label false
                                                                    :placeholder "Description"})]
         [entity.ui/persisted-attr note :entity/video field-params]

         [entity.ui/persisted-attr note
          :entity/field-entries
          {:entity/fields    (:entity/fields note)
           :membership/roles roles
           :field/can-edit?  can-edit?}]


         [entity.ui/persisted-attr note :entity/fields
          {:field/can-edit? can-edit?}]

         [note-members note field-params]

         (when can-edit?
           [ui/action-button {:on-click (fn [_]
                                          (p/let [result (data/delete! nil {:note-id (sch/unwrap-id (:note-id params))})]
                                            (routing/dissoc-router! :router/modal)
                                            result))}
            "delete"])]]]
      [ui/error-view
       {:error "Note not found"}])))


(ui/defview card
  {:key :entity/id}
  [{:as   entity
    :keys [entity/parent
           entity/title
           entity/description
           entity/fields
           entity/field-entries
           note/outline-color]}]
  [:a.flex-v.hover:bg-gray-100.rounded-lg.bg-slate-100.py-3.gap-3.
   {:href (routing/entity-path entity 'ui/show)
    :class "outline outline-4"
    :style {:outline-color outline-color}}
   [:div.flex.relative.gap-3.items-start.px-3.cursor-default.flex-auto
    [:div.flex-grow.flex-v.gap-1
     [:div.leading-snug.line-clamp-2.font-semibold.text-lg title]
     [:div.text-gray-500
      (field.ui/show-prose description)]
     [:div.text-gray-500.contents
      (field.ui/show-entries (filter :field/show-on-card? fields)
                             field-entries)]]]

   [member.ui/members-for-card entity]])
