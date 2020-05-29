(ns org.sparkboard.slack.account-bridge-node
  (:require [org.sparkboard.firebase.tokens :as tokens]
            [kitchen-async.promise :as p]
            [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.http :as http]
            [applied-science.js-interop :as j]))

;; this code runs in the legacy node server and handles inbound links (from slack to sparkboard).
;;
;; Slack sign-in status is authoritative - if the user has linked accounts,
;; we automatically sign them in to Sparkboard when they land here.
;;

(defn slack-link-handler [^js req ^js res next]
  (p/try (p/let [{:as token-payload
                  :keys [slack/user-id
                         slack/app-id
                         slack/team-id
                         sparkboard/board-id
                         sparkboard/account-id
                         redirect]} (tokens/decode (.. req -query -token))
                 authed-account (j/get-in req [:user :firebaseAccount])]

           (assert redirect)

           (cond account-id
                 ;; We know the user's Sparkboard account: sign them in.
                 (p/do (.logInFirebaseAccount req board-id account-id)
                       (.redirect res redirect))

                 authed-account
                 ;; user is already signed in to Sparkboard -- link their account to Slack.
                 (p/try (p/do
                          ;; TODO
                          ;; visible confirmation step to link the two accounts?
                          (slack-db/link-user-to-account!   ;; link the accounts
                            {:slack/team-id team-id
                             :slack/user-id user-id
                             :sparkboard/account-id authed-account})
                          (http/post+ (j/get req "sparkboard.jvm/root") ;; update the user's home tab
                                      {:body/content-type :transit+json
                                       :body {:sparkboard [:update-home! {:slack/team-id team-id
                                                                          :slack/user-id user-id}]}})
                          (.redirect res redirect))         ;; we're done
                        (p/catch js/Error e
                          (-> res (.status 500) (.send (.-message e)))))

                 :else
                 ;; have the user sign in, then return here with the same token to link their account.
                 (.redirect res (str "/login?redirect=" (js/encodeURIComponent (.-url req))))))
         (p/catch js/Error ^js e
           (.send res (.-message e))
           (throw e))))

