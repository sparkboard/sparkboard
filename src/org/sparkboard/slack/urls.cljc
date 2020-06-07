(ns org.sparkboard.slack.urls
  (:require [org.sparkboard.firebase.tokens :as tokens]
            [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.promise :as p]
            [org.sparkboard.server.env :as env]
            [taoensso.timbre :as log]
            [lambdaisland.uri :as uri]
            [org.sparkboard.js-convert :refer [clj->json]]))

(defn sparkboard-host
  ([domain]
   (sparkboard-host (:env env/config "dev") domain))
  ([env domain]
   (if (-> env/config :dev/mock-sparkboard?)
     (str (:sparkboard/jvm-root env/config) "/mock/" domain)
     (case env
       "dev" (str "http://" domain ".test:4999")
       "staging" (str "http://" domain ".sparkboard.org")
       "prod" (str "https://" domain)))))

(defn app-redirect [{:as params :keys [app team domain]}]
  {:pre [app team domain]}
  (str "https://" domain "." "slack.com/app_redirect?" (uri/map->query-string (dissoc params :domain))))

(defn install-slack-app
  "Returns a link that will lead user to install/reinstall app to a workspace"
  [& [{:as params
       :keys [sparkboard/jvm-root
              sparkboard/board-id
              sparkboard/account-id
              slack/team-id
              dev/local?]
       :or {jvm-root (-> env/config :sparkboard/jvm-root)}}]]
  {:pre [(or team-id                                        ;; reinstall
             (and board-id account-id)                      ;; new install + link board
             local?
             )]}
  (str jvm-root "/slack/install?state=" (tokens/encode (dissoc params :sparkboard/jvm-root)
                                                       {:expires-in (* 60 60 24 60)})))

(defn on-sparkboard [{:as context
                      :slack/keys [team-id app-id user-id]
                      :sparkboard/keys [board-id]
                      :keys [env]} redirect]
  {:pre [env board-id user-id team-id redirect]}
  (log/trace ::on-sparkboard redirect context)
  (p/let [domain (slack-db/board-domain board-id)
          payload (-> context
                      (select-keys [:slack/team-id
                                    :slack/user-id])
                      (assoc :redirect redirect))]
    (log/trace :sparkboard/slack-link payload)
    (str (sparkboard-host env domain)
         "/slack-link?token=" (tokens/encode payload))))

(defn link-sparkboard-account
  "Sends the user to Sparkboard to link their account, then redirects them back to Slack"
  [{:as context :slack/keys [app-id team-id team-domain*]}]
  (on-sparkboard context (app-redirect {:app app-id :team team-id :domain @team-domain*})))

