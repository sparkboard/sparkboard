(ns server.slack
  "Slack API"
  (:require [applied-science.js-interop :as j]
            [lambdaisland.uri :as uri]
            [server.blocks :as blocks]
            [server.common :refer [clj->json config]]
            [server.http :as http]
            [kitchen-async.promise :as p]))

(def base-uri "https://slack.com/api/")
(def bot-token (-> config :slack :bot-user-oauth-token))

;; TODO refactir some of content-type & headers into a `def`


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Direct HTTP calls
(defn get+ [family-method]
  (http/fetch-json+ (str base-uri family-method)
                    (j/lit {:method "GET"
                            :Content-Type "application/json; charset=utf-8"
                            :headers {:Authorization (str "Bearer " bot-token)}})))

;; XXX may or may not work for the rest of the API; breaks on chat.PostMessage for unknown reasons I suspect are related to node-fetch possibly mixing URL parameters with a JSON body, which makes Slack choke with "channel_not_found"
(defn post+ [family-method body]
  (http/fetch-json+ (str base-uri family-method)
                    (j/lit {:method "post"
                            :Content-Type "application/json; charset=utf-8"
                            :headers {:Authorization (str "Bearer " bot-token)}
                            :body (clj->js body) #_(.stringify js/JSON (clj->js body))})))



(defn post-query-string+ [family-method query-params]
  ;; This fn is a hack to work around broken JSON bodies in Slack's API
  (http/fetch-json+ (str base-uri family-method "?" (uri/map->query-string query-params))
                    (j/lit {:method "post"
                            :Content-Type "application/json; charset=utf-8"
                            :headers {:Authorization (str "Bearer " bot-token)}})))

(comment
  (p/-> (get+ "users.list")
        println))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Convenience wrappers over individual endpoints
(defn views-open! [trigger-id blocks]
  (println "[views-open!] JSON blocks:" (blocks/to-json blocks))
  (p/->> (post-query-string+ "views.open"
                             {:trigger_id trigger-id
                              :view (blocks/to-json blocks)})
         ;; TODO better callback
         (println "slack views.open response:")))

(defn views-update! [view-id blocks]
  (p/->> (post-query-string+ "views.update"
                             {:view_id view-id
                              :view (blocks/to-json blocks)})
         ;; TODO better callback
         (println "slack views.update response:")))
