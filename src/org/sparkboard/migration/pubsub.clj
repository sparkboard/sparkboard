(ns org.sparkboard.migration.pubsub
  (:require [jonotin.core :as jonotin]
            [jsonista.core :as json]
            [org.sparkboard.env :as env]
            [mount.core :as mo]))

;; to be run in prod - consumes stream of updates from mongodb + firebase

(def service-account-json (env/get :firebase/service-account))
(def service-account (json/read-value service-account-json))

(defn subscribe! [{:keys [on-message on-error]}]
  (jonotin/subscribe! {:project-name (service-account "project_id")
                       :credentials service-account-json
                       :subscription-name (env/get :google.pubsub/legacy-log-subscription)
                       :handle-msg-fn on-message
                       :handle-error-fn on-error}))

(def handle-message (partial prn [:pubsub :message]))
(def handle-error (partial prn [:pubsub :error]))

(mo/defstate subscription
  :start  (subscribe! {:on-message handle-message
                       :on-error handle-error})
  :stop (.stopAsync subscription))

(comment
  (mo/start #'subscription))