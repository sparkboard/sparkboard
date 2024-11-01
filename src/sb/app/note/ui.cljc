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
            [sb.authorize :as az]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [sb.util :as u]))

;; TODO test this modal outside of owning board
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
          [ui/action-button {:on-click #(entity.data/save-attributes! {:entity {:entity/id (:entity/id note)
                                                                                :entity/draft? false}})
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

         [member.ui/for-modal note field-params]

         (when-let [delete! (entity.data/delete!-authorized {:entity-id (sch/unwrap-id (:note-id params))})]
           [ui/action-button
            {:class "bg-white"
             :on-click (fn [_]
                         (p/let [result (delete!)]
                           (routing/dissoc-router! :router/modal)
                           result))}
            (t :tr/delete)])]]]
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
