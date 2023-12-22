(ns sb.app.board.admin-ui
  (:require [inside-out.forms :as io]
            [sb.app.board.data :as data]
            [sb.app.entity.ui :as entity.ui :refer [use-persisted-attr view-field]]
            [sb.app.views.header :as header]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [t]]
            [yawn.hooks :as h]))

;; issue
;; desire is to create a toplevel board



(ui/defview settings
  {:route "/b/:board-id/settings"}
  [{:as params :keys [board-id]}]
  (when-let [board (data/settings params)]
    (prn (:board/member-fields board))
    (ui/with-form [?board
                   {:entity/title                    ?title
                    :entity/description              ?description
                    :entity/domain-name              ?domain-name
                    :image/avatar                    ?avatar
                    :board/member-fields             (?member-fields :many
                                                                     {:field/id                    ?id
                                                                      :field/type                  ?type
                                                                      :field/label                 ?label
                                                                      :field/hint                  ?hint
                                                                      :field/options
                                                                      (?options :many
                                                                                {:field-option/label ?label
                                                                                 :field-option/value ?value
                                                                                 :field-option/color ?color})

                                                                      :field/required?             ?required?
                                                                      :field/show-as-filter?       ?show-as-filter?
                                                                      :field/show-at-registration? ?show-at-registration?
                                                                      :field/show-on-card?         ?show-on-card?})
                    :board/project-fields            (?project-fields :many
                                                                      {:field/id                    ?id
                                                                       :field/type                  ?type
                                                                       :field/label                 ?label
                                                                       :field/hint                  ?hint
                                                                       :field/options
                                                                       (?options :many
                                                                                 {:field-option/label ?label
                                                                                  :field-option/value ?value
                                                                                  :field-option/color ?color})

                                                                       :field/required?             ?required?
                                                                       :field/show-as-filter?       ?show-as-filter?
                                                                       :field/show-at-registration? ?show-at-registration?
                                                                       :field/show-on-card?         ?show-on-card?})
                    :board/registration-open?        ?registration-open?
                    :board/registration-url-override ?registration-url-override
                    :board/registration-page-message ?registration-page-message
                    :board/invite-email-text         ?invite-email-text}
                   :init board
                   :form/entity.id (:entity/id board)]
      [:<>
       (header/entity board (list (entity.ui/settings-button board)))
       [radix/accordion {:class    "max-w-[600px] mx-auto my-6 flex-v gap-6"
                         :multiple true}

        [:div.field-label (t :tr/basic-settings)]
        [:div.flex-v.gap-4

         (view-field ?title)
         (view-field ?description)
         (view-field ?domain-name)
         (view-field ?avatar {:label (t :tr/image.logo)})]


        [:div.field-label (t :tr/projects-and-members)]
        [:div.flex-v.gap-4
         (view-field ?member-fields)
         (view-field ?project-fields)]


        [:div.field-label (t :tr/registration)]
        [:div.flex-v.gap-4
         (view-field ?registration-open?)
         (view-field ?registration-url-override)
         (view-field ?registration-page-message)
         (view-field ?invite-email-text)]]



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

       ])))