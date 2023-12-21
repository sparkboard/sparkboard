(ns sb.app
  (:require [sb.app.account.ui]
            [sb.app.asset.ui]
            [sb.app.board.ui]
            [sb.app.board.admin-ui]
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
            [sb.app.org.ui]
            [sb.app.project.ui]
            [sb.app.social-feed.ui]
            [sb.app.vote.ui]
            [org.sparkboard.slack.schema]
            [sb.transit :as t]
            [inside-out.forms :as io]
            [sb.i18n :refer [tr]]))

#?(:cljs
   (def client-endpoints (t/read (shadow.resource/inline "public/js/sparkboard.views.transit.json"))))

(def global-field-meta
  {:account/email               {:view       field.ui/text-field
                                 :props      {:type        "email"
                                              :placeholder (tr :tr/email)}
                                 :validators [form.ui/email-validator]}
   :account/password            {:view       field.ui/text-field
                                 :props      {:type        "password"
                                              :placeholder (tr :tr/password)}
                                 :validators [(io/min-length 8)]}
   :entity/title                {:validators [(io/min-length 3)]}
   :board/project-fields        {:view  field.admin-ui/fields-editor}
   :board/member-fields         {:view  field.admin-ui/fields-editor}
   :field/label                 {:view  field.ui/text-field}
   :field/hint                  {:view  field.ui/text-field}
   :entity/domain-name          {:view       domain.ui/domain-field
                                 :validators (domain.ui/validators)}
   :image/avatar                {:view field.ui/image-field}})