(ns org.sparkboard.slack.urls
  (:require [org.sparkboard.firebase.tokens :as tokens]
            [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.promise :as p]
            [org.sparkboard.server.env :as env]
            [taoensso.timbre :as log]
            [lambdaisland.uri :as uri]
            [org.sparkboard.js-convert :refer [clj->json]]))

(defn- sparkboard-host [env domain]
  (case env
    "dev" (str "http://" domain ".test:4999")
    "staging" (str "http://" domain ".sparkboard.org")
    "prod" (str "https://" domain)))

(defn app-redirect [params]
  (str "https://slack.com/app_redirect?" (uri/map->query-string params)))

(defn slack-home [app-id team-id]
  (app-redirect {:team team-id :app app-id}))

(defn install-slack-app
  "Returns a link that will lead user to install/reinstall app to a workspace"
  [& [{:as params
       :keys [sparkboard/jvm-root
              sparkboard/board-id
              sparkboard/account-id
              slack/team-id]}]]
  {:pre [(or team-id                                        ;; reinstall
             (and board-id account-id)                      ;; new install + link board
             )]}
  (str jvm-root "/slack/install?state=" (tokens/encode (dissoc params :sparkboard/jvm-root))))

(defn on-sparkboard [{:as context
                      :slack/keys [team-id app-id user-id]
                      :sparkboard/keys [board-id]
                      :keys [env]} redirect]
  {:pre [env board-id user-id team-id redirect]}
  (log/trace ::on-sparkboard context)
  (p/let [domain (slack-db/board-domain board-id)]
    (str (if (-> env/config :dev/mock-sparkboard?)
           (str (:sparkboard/jvm-root env/config) "/mock")
           (sparkboard-host env domain))
         "/slack-link?token="
         (-> context
             (select-keys [:slack/team-id
                           :slack/app-id
                           :slack/user-id
                           :sparkboard/board-id])
             (assoc :redirect redirect)
             (tokens/encode)))))

(defn link-sparkboard-account
  "Sends the user to Sparkboard to link their account, then redirects them back to Slack"
  [context]
  (on-sparkboard context
                 (slack-home (:slack/app-id context)
                             (:slack/team-id context))))

