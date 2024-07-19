(ns sb.app.board.admin-ui
  (:require [inside-out.forms :as io]
            [sb.app.board.data :as data]
            [sb.app.entity.ui :as entity.ui :refer [persisted-attr]]
            [sb.app.views.header :as header]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [t]]
            [sb.util :as u]
            [yawn.hooks :as h]))

;; issue
;; desire is to create a toplevel board



(ui/defview settings
  {:route "/b/:board-id/settings"}
  [{:as params :keys [board-id]}]
  (when-let [board (data/settings params)]
    (let [colors        (->> [(->> board :entity/member-tags (keep :tag/color))
                              (->> board :entity/member-fields (mapcat :field/options) (keep :field-option/color))
                              (->> board :entity/project-fields (mapcat :field/options) (keep :field-option/color))]
                             (apply concat)
                             (into #{}))
          use-persisted (fn [attr & [props]]
                          (persisted-attr board attr (merge {:field/can-edit?  true
                                                             :field/color-list colors} props)))]

      [:<>
       (header/entity board nil)
       #_(for [color colors]
           [:div.w-8.h-8.m-1.rounded {:key color :style {:background-color color}}])
       [radix/accordion {:class    "max-w-[600px] mx-auto my-6 flex-v gap-6"
                         :multiple true}

        [:div.field-label (t :tr/basic-settings)]

        [:div.flex-v.gap-4

         (use-persisted :entity/title)
         (use-persisted :entity/description)
         (use-persisted :entity/domain-name)
         (use-persisted :image/avatar {:field/label (t :tr/logo)})
         (use-persisted :image/logo-large {:field/label (t :tr/logo-large)})
         (use-persisted :image/sub-header {:field/label (t :tr/image.sub-header)})
         (use-persisted :image/footer {:field/label (t :tr/image.footer)})
         (use-persisted :image/background {:field/label (t :tr/image.background)})
         (use-persisted :entity/member-tags)
         ]


        [:div.field-label (t :tr/projects-and-members)]
        [:div.flex-v.gap-4
         (use-persisted :board/sticky-color)
         (use-persisted :entity/member-fields)
         (use-persisted :entity/project-fields)
         ]


        [:div.field-label (t :tr/registration)]
        [:div.flex-v.gap-4
         (use-persisted :entity/admission-policy)
         (use-persisted :board/registration-url-override)
         (use-persisted :board/registration-page-message)
         (use-persisted :board/invite-email-text)]

        [:div.field-label (t :tr/community-vote)]
        [:div.flex-v.gap-4
         (use-persisted :member-vote/open? {:field/label (t :tr/vote-open)})]
        ]



       ;; TODO
       ;; - :board/project-sharing-buttons
       ;; - :board/member-tags

       ;; Registration
       ;; - :board/registration-invitation-email-text
       ;; - :board/registration-newsletter-field?
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
