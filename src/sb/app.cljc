(ns sb.app
  (:require [inside-out.forms :as io]
            [org.sparkboard.slack.schema]
            [sb.app.account.ui]
            [sb.app.asset.ui]
            [sb.app.board.admin-ui]
            [sb.app.board.ui]
            [sb.app.chat.ui]
            [sb.app.collection.ui]
            [sb.app.content.ui]
            [sb.app.discussion.ui]
            [sb.app.domain-name.ui :as domain.ui]
            [sb.app.entity.ui]
            [sb.app.field.admin-ui :as field.admin-ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.member.ui]
            [sb.app.notification.ui]
            [sb.app.org.admin-ui]
            [sb.app.org.ui]
            [sb.app.project.ui]
            [sb.app.social-feed.ui]
            [sb.app.vote.ui]
            [sb.i18n :refer [t]]
            [sb.transit :as t]
            [sb.util :as u]))

#?(:cljs
   (def client-endpoints (t/read (shadow.resource/inline "public/js/sparkboard.views.transit.json"))))

(defn fields-editor-field []
  (io/field :many (u/prune {:field/id                    ?id
                            :field/type                  ?type
                            :field/label                 ?label
                            :field/hint                  ?hint
                            :field/options               (?options :many {:field-option/label ?label
                                                                          :field-option/value ?value
                                                                          :field-option/color ?color})
                            :field/required?             ?required?
                            :field/show-as-filter?       ?show-as-filter?
                            :field/show-at-registration? ?show-at-registration?
                            :field/show-on-card?         ?show-on-card?})))

(def global-field-meta
  {:account/email        {:view       field.ui/text-field
                          :props      {:type        "email"
                                       :placeholder (t :tr/email)}
                          :validators [form.ui/email-validator]}
   :account/password     {:view       field.ui/text-field
                          :props      {:type        "password"
                                       :placeholder (t :tr/password)}
                          :validators [(io/min-length 8)]}
   :entity/title         {:validators [(io/min-length 3)]}
   :board/project-fields {:view       field.admin-ui/fields-editor
                          :make-field fields-editor-field}
   :board/member-fields  {:view       field.admin-ui/fields-editor
                          :make-field fields-editor-field}
   :field/label          {:view field.ui/text-field}
   :field/hint           {:view field.ui/text-field}
   :field/options        {:view field.admin-ui/options-editor}
   :entity/domain-name   {:view       domain.ui/domain-field
                          :validators (domain.ui/validators)}
   :image/avatar         {:view field.ui/image-field}})