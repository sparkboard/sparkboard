(ns sb.app.board.admin-ui
  (:require [sb.app.views.ui :as ui]
            [sb.app.board.data :as data]
            [sb.app.views.header :as header]
            [sb.app.form.ui :as form.ui]
            [sb.app.entity.ui :as entity.ui :refer [use-persisted]]
            [sb.app.field.ui :as field.ui]
            [sb.app.domain-name.ui :as domain.ui]
            [sb.app.field.admin-ui :as field.admin-ui]
            [sb.app.views.radix :as radix]
            [sb.i18n :refer [tr]]))

(ui/defview settings
  {:route "/b/:board-id/settings"}
  [{:as params :keys [board-id]}]
  (let [board (data/settings params)]
    [:<>
     (header/entity board)
     [radix/accordion {:class "max-w-[600px] mx-auto my-6 flex-v gap-6"
                       :multiple true}

      [:div.field-label (tr :tr/basic-settings)]
      [:div.flex-v.gap-4
       (use-persisted board :entity/title)
       (use-persisted board :entity/description)
       (use-persisted board :entity/domain-name)
       (use-persisted board :image/avatar {:label (tr :tr/image.logo)})]


      [:div.field-label (tr :tr/projects-and-members)]
      [:div.flex-v.gap-4
       (field.admin-ui/fields-editor board :board/member-fields)
       (field.admin-ui/fields-editor board :board/project-fields)]


      [:div.field-label (tr :tr/registration)]
      [:div.flex-v.gap-4
       (use-persisted board :board/registration-open?)
       (use-persisted board :board/registration-url-override)
       (use-persisted board :board/registration-page-message)
       (use-persisted board :board/invite-email-text)]]



     ;; TODO
     ;; - :board/project-sharing-buttons
     ;; - :board/member-tags

     ;; Registration
     ;; - :board/registration-invitation-email-text
     ;; - :board/registration-newsletter-field?
     ;; - :board/registration-open?
     ;; - :board/registration-message
     ;; - :board/registration-url-override
     ;; - :board/registration-codes

     ;; Theming
     ;; - border radius
     ;; - headline font
     ;; - accent color

     ;; Sponsors
     ;; - logo area with tiered sizes/visibility

     ;; Sticky Notes
     ;; - schema: a new entity type (not a special kind of project)
     ;; - modify migration based on ^new schema
     ;; - color is picked per sticky note
     ;; - sticky notes can include images/videos

     ]))