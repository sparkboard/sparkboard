(ns org.sparkboard.slack.account-bridge-node
  (:require [org.sparkboard.firebase.tokens :as tokens]
            [kitchen-async.promise :as p]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.http :as http]
            [org.sparkboard.js-convert :refer [->clj]]))

;; this fn is called by the legacy node server and handles inbound links (from slack to sparkboard).
(defn slack-link-proxy [legacy-session]
  (let [{:keys [req-token
                req-account-id
                req-url
                res-redirect]} (->clj legacy-session)]
    (p/let [{:keys [slack/user-id
                    slack/team-id]} (tokens/decode req-token)]
      (if req-account-id
        ;; we're signed in - link the account
        (res-redirect
          (str (env/config :sparkboard/jvm-root)
               "/slack/link-account?token="
               (tokens/encode
                 {:sparkboard/server-request? true
                  :slack/team-id team-id
                  :slack/user-id user-id
                  :sparkboard/account-id req-account-id})))
        ;; sign in & then come back here
        (res-redirect (str "/login?redirect=" (js/encodeURIComponent req-url)))))))

