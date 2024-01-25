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
            [sb.app.membership.ui]
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

(def global-field-meta
  {:string                  {:view field.ui/text-field}
   :http/url                {:view field.ui/text-field}
   :boolean                 {:view field.ui/checkbox-field}
   :project/badges          {:view field.ui/badges-field}
   :prose/as-map            {:view field.ui/prose-field}
   :entity/tags             {:view field.ui/tags-field}
   :board/sticky-color      {:view field.ui/color-field-with-label
                             :props {:field/label "Sticky color"}}
   :account/email           {:props      {:type        "email"
                                          :placeholder (t :tr/email)}
                             :validators [form.ui/email-validator]}
   :account/password        {:view       field.ui/text-field
                             :props      {:type        "password"
                                          :placeholder (t :tr/password)}
                             :validators [(io/min-length 8)]}
   :entity/title            {:validators [(io/min-length 3)]}
   :field/options           {:view field.admin-ui/options-editor}
   :entity/domain-name      {:view domain.ui/domain-field}
   :entity/video            {:view field.ui/video-field}
   :entity/fields           {:view field.admin-ui/fields-editor}
   :entity/member-tags      {:view field.admin-ui/tags-editor}
   :entity/field-entries    {:view field.ui/entries-field}
   :entity/admission-policy {:view  field.ui/select-field
                             :props {:field/wrap    keyword
                                     :field/unwrap  #(subs (str %) 1)
                                     :field/options [{:field-option/value :admission-policy/open
                                                      :field-option/label (t :tr/anyone-may-join)}
                                                     {:field-option/value :admission-policy/invite-only
                                                      :field-option/label (t :tr/invite-only)}]}}
   :asset/as-map            {:view field.ui/image-field}})