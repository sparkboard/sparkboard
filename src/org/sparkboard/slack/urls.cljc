(ns org.sparkboard.slack.urls
  (:require [org.sparkboard.firebase.tokens :as tokens]
            [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.promise :as p]))

(defn- sparkboard-host [env domain]
  (case env
    "dev" (str "http://" domain ".test:4999")
    "staging" (str "http://" domain ".sparkboard.org")
    "prod" (str "https://" domain)))

(defn slack-home [app-id team-id]
  (str "https://slack.com/app_redirect?team=" team-id "&app=" app-id))

(defn install-slack-app
  "Returns a link that will lead user to install/reinstall app to a workspace"
  [& [{:as params
       :keys [sparkboard.jvm/root
              sparkboard/board-id
              sparkboard/account-id
              slack/team-id]}]]
  {:pre [(or team-id                                        ;; reinstall
             (and board-id account-id)                      ;; new install + link board
             )]}
  (str root "/slack/install?state=" (tokens/encode (dissoc params :sparkboard.jvm/root))))

(defn on-sparkboard [{:as props
                      :slack/keys [team-id app-id user-id]
                      :sparkboard/keys [board-id]
                      :keys [env]} redirect]
  {:pre [env board-id user-id team-id redirect]}
  (p/let [domain (slack-db/board-domain board-id)]
    (str (sparkboard-host env domain)
         "/slack-link?token="
         (-> props
             (select-keys [:slack/team-id
                           :slack/app-id
                           :slack/user-id
                           :sparkboard/board-id])
             (assoc :redirect redirect)
             (tokens/encode)))))

(defn link-sparkboard-account [props]
  (on-sparkboard props
                 (slack-home (:slack/app-id props)
                             (:slack/team-id props))))

