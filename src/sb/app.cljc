(ns sb.app
  (:require [sb.app.account.ui]
            [sb.app.asset.ui]
            [sb.app.board.ui]
            [sb.app.chat.ui]
            [sb.app.collection.ui]
            [sb.app.content.ui]
            [sb.app.discussion.ui]
            [sb.app.domain.ui]
            [sb.app.entity.ui]
            [sb.app.field.admin-ui]
            [sb.app.field.ui]
            [sb.app.member.ui]
            [sb.app.notification.ui]
            [sb.app.org.ui]
            [sb.app.project.ui]
            [sb.app.social-feed.ui]
            [sb.app.vote.ui]
            [org.sparkboard.slack.schema]
            [sb.transit :as t]))

#?(:cljs
   (def client-endpoints (t/read (shadow.resource/inline "public/js/sparkboard.views.transit.json"))))