(ns server.slack
  "Slack API"
  (:require [lambdaisland.uri :as uri]
            [server.common :refer [clj->json config]]
            [server.http :as http]))

(def base-uri "https://slack.com/api/")

;; TODO refactir some of content-type & headers into a `def`


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Direct HTTP calls
(defn get! [family-method callback-fn]
  (-> (http/fetch+ http/decode-json
                   (str base-uri family-method)
                   #js {:method "GET"
                        "Content-Type" "application/json; charset=utf-8"
                        :headers #js {"Authorization" (str "Bearer " (-> config :slack :bot-user-oauth-token))}})
      (.then callback-fn)))

;; XXX may or may not work for the rest of the API; breaks on chat.PostMessage for unknown reasons I suspect are related to node-fetch possibly mixing URL parameters with a JSON body, which makes Slack choke with "channel_not_found"
(defn post! [family-method body callback-fn]
  (-> (http/fetch+ http/decode-json
                   (str base-uri family-method)
                   #js {:method "post"
                        "Content-Type" "application/json; charset=utf-8"
                        :headers #js {"Authorization" (str "Bearer " (-> config :slack :bot-user-oauth-token))}
                        :body (clj->js body) #_(.stringify js/JSON (clj->js body))})
      (.then callback-fn)))

(defn post-query-string! [family-method query-params callback-fn]
  ;; This fn is a hack to work around broken JSON bodies in Slack's API
  (-> (http/fetch+ http/decode-json
                   (str base-uri family-method "?" (uri/map->query-string query-params))
                   #js {:method "post"
                        "Content-Type" "application/json; charset=utf-8"
                        :headers #js {"Authorization" (str "Bearer " (-> config :slack :bot-user-oauth-token))}})
      (.then callback-fn)))

(comment
 (get! "users.list"
       ;; FIXME detect and log errors
       (fn [rsp] (println rsp)))
 )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Convenience wrappers over individual endpoints
(defn views-open! [trigger-id blocks]
  (println "[views-open!] JSON blocks:" (clj->json blocks))
  (post-query-string! "views.open"
                      {:trigger_id trigger-id
                       :view (clj->json blocks)}
                      ;; TODO better callback
                      (fn [rsp] (println "slack views.open response:" rsp))))

(defn views-update! [view-id blocks]
  (post-query-string! "views.update"
                      {:view_id view-id
                       :view (clj->json blocks)}
                      ;; TODO better callback
                      (fn [rsp] (println "slack views.update response:" rsp))))
