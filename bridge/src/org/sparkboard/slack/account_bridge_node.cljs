(ns org.sparkboard.slack.account-bridge-node
  (:require [org.sparkboard.firebase-tokens :as tokens]
            [kitchen-async.promise :as p]
            [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.http :as http]
            [org.sparkboard.slack.links :as links]
            [applied-science.js-interop :as j]))

(defn slack-link-handler [^js req ^js res next]
  (p/try (p/let [{:as token-payload
                  :keys [slack/user-id
                         slack/app-id
                         slack/team-id
                         sparkboard/board-id
                         sparkboard/account-id
                         redirect]} (tokens/decode (.. req -query -token))
                 authed-account (j/get-in req [:user :firebaseAccount])]
           (prn :PAYLOAD token-payload)
           (assert redirect)
           (cond account-id
                 (if (= account-id authed-account)
                   (.redirect res redirect)
                   (p/do (.logInFirebaseAccount req board-id account-id)
                         (.redirect res redirect)))
                 authed-account
                 (p/try (p/do
                          (prn :linking-account)
                          (slack-db/link-user-to-account!
                            (assoc token-payload :sparkboard/account-id authed-account))
                          (prn :linked)
                          (http/post+ (.-lambdaRoot req)
                                      {:format :transit+json
                                       :body {:sparkboard {:sparkboard/action :update-home!
                                                           :slack/team-id team-id
                                                           :slack/user-id user-id}}})
                          (.redirect res redirect))
                        (p/catch js/Error e
                          (-> res (.status 500) (.send (.-message e)))))
                 :else
                 (.redirect res (str "/login?redirect=" (js/encodeURIComponent (.-url req))))))
         (p/catch js/Error ^js e
           (.send res (.-message e))
           (throw e))))

