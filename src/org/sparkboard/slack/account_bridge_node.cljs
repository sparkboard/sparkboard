(ns org.sparkboard.slack.account-bridge-node
  (:require [org.sparkboard.firebase.tokens :as tokens]
            [kitchen-async.promise :as p]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.http :as http]
            [org.sparkboard.js-convert :refer [->clj]]))

;; this fn is called by the legacy node server and handles inbound links (from slack to sparkboard).
(defn slack-link-proxy [legacy-session]
  (let [{:keys [req-account-id
                req-url
                req-create-session
                req-token
                res-redirect
                res-error]} (->clj legacy-session)]
    (p/try
      (p/let [{:as token-payload
               :keys [slack/user-id
                      slack/team-id
                      sparkboard/board-id
                      redirect]
               sparkboard-account-id :sparkboard/account-id} (tokens/decode req-token)
              action (cond sparkboard-account-id :sign-in   ;; We know the user's Sparkboard account
                           req-account-id :link-account     ;; user is already signed in to Sparkboard
                           :else :auth-redirect)]           ;; user must sign in, then return here
        (assert redirect "Redirect link not found")
        (case action
          :sign-in
          (p/do (req-create-session board-id sparkboard-account-id)
                (res-redirect redirect))

          :link-account
          (p/do
            (http/post+ (str (env/config :sparkboard/jvm-root) "/slack/server-action")
                        {:auth/token (tokens/encode {:sparkboard/server-request? true})
                         :body/content-type :transit+json
                         :body {:action :link-account!
                                :slack/team-id team-id
                                :slack/user-id user-id
                                :sparkboard/account-id req-account-id}})
            (res-redirect redirect))
          :auth-redirect
          (res-redirect (str "/login?redirect=" (js/encodeURIComponent req-url)))))
      (p/catch js/Error ^js e
        (js/console.error e)
        (res-error 400 (case (env/config :env "dev")
                         "dev" (.-stack e)
                         (.-message e)))))))

