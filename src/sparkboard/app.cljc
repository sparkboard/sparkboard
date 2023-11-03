(ns sparkboard.app
  (:require [sparkboard.slack.schema]
            [sparkboard.app.account]
            [sparkboard.app.assets]
            [sparkboard.app.board]
            [sparkboard.app.chat]
            [sparkboard.app.collection]
            [sparkboard.app.content]
            [sparkboard.app.discussion]
            [sparkboard.app.domain]
            [sparkboard.app.entity]
            [sparkboard.app.field]
            [sparkboard.app.member]
            [sparkboard.app.notification]
            [sparkboard.app.org]
            [sparkboard.app.project]
            [sparkboard.app.social-feed]
            [sparkboard.app.vote]
            [sparkboard.transit :as t]))

#?(:cljs
   (def client-endpoints (t/read (shadow.resource/inline "public/js/sparkboard-views.transit.json"))))