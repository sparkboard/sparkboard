(ns org.sparkboard.migration.pubsub
  (:require [jonotin.core :as jonotin]
            [org.sparkboard.util.js-convert :refer [clj->json]]
            [org.sparkboard.server.env :as env]))

;; to be run in prod - consumes stream of updates from mongodb + firebase

(def service-account (env/config :firebase/service-account))
(def service-account-json (clj->json service-account))


(defn subscribe! [{:keys [on-message on-error]}]
  (jonotin/subscribe! {:project-name (service-account "project_id")
                       :credentials service-account-json
                       :subscription-name (env/config :google.pubsub/legacy-log-subscription)
                       :handle-msg-fn on-message
                       :handle-error-fn on-error}))

(def handle-message (partial prn [:pubsub :message]))
(def handle-error (partial prn [:pubsub :error]))

(defonce subscription (atom nil))

(defn stop []
  (some-> @subscription (.stopAsync)))

(defn start []
  (stop)
  (reset! subscription
          (subscribe! {:on-message handle-message
                       :on-error handle-error})))

(comment
  (start))
