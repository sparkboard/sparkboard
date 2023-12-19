(ns sparkboard.app
  (:require [sparkboard.slack.schema]
            [sparkboard.app.account.ui]
            [sparkboard.app.asset.ui]
            [sparkboard.app.board.ui]
            [sparkboard.app.chat.ui]
            [sparkboard.app.collection.ui]
            [sparkboard.app.content.ui]
            [sparkboard.app.discussion.ui]
            [sparkboard.app.domain.ui]
            [sparkboard.app.entity.ui]
            [sparkboard.app.field.admin-ui]
            [sparkboard.app.member.ui]
            [sparkboard.app.notification.ui]
            [sparkboard.app.org.ui]
            [sparkboard.app.project.ui]
            [sparkboard.app.social-feed.ui]
            [sparkboard.app.vote.ui]
            [sparkboard.transit :as t]))

#?(:cljs
   (def client-endpoints (t/read (shadow.resource/inline "public/js/sparkboard-views.transit.json"))))