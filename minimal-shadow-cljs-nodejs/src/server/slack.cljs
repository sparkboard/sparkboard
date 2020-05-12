(ns server.slack
  "Slack API"
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [clojure.string :as string]
            [lambdaisland.uri :as uri]
            ["node-fetch"]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Environment variables/config
(def config (cljs.reader/read-string (j/get js/process.env :SPARKBOARD_CONFIG)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; HTTP requests
(def node-fetch (js/require "node-fetch"))

(defn- fetch+ [decode-fn url opts]
  (-> (node-fetch url opts)
      (.then (fn [^js/Response res] (if (.-ok res)
                                     (decode-fn res)
                                     (throw (ex-info "Invalid network request"
                                                     {:status (.-status res)})))))))

(defn decode-json [^js/Response resp] (.json resp))

(def ^js/Promise fetch-json+ (partial fetch+ decode-json))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Slack API fns

(def base-uri "https://slack.com/api/")

(defn get! [family-method callback-fn]
  (-> (fetch+ decode-json
              (str base-uri family-method)
              #js {:method "GET"
                   "Content-Type" "application/json; charset=utf-8"
                   :headers #js {"Authorization" (str "Bearer " (-> config :slack :bot-user-oauth-token))}})
      (.then callback-fn)))

;; XXX may or may not work for the rest of the API; breaks on chat.PostMessage for unknown reasons I suspect are related to node-fetch possibly mixing URL parameters with a JSON body, which makes Slack choke with "channel_not_found"
(defn post! [family-method body callback-fn]
  (-> (fetch+ decode-json
              (str base-uri family-method)
              #js {:method "post"
                   "Content-Type" "application/json; charset=utf-8"
                   :headers #js {"Authorization" (str "Bearer " (-> config :slack :bot-user-oauth-token))}
                   :body (clj->js body) #_(.stringify js/JSON (clj->js body))})
      (.then callback-fn)))

(defn post-query-string! [family-method query-params callback-fn]
  (-> (fetch+ decode-json
              (str base-uri family-method "?" (uri/map->query-string query-params))
              #js {:method "post"
                   "Content-Type" "application/json; charset=utf-8"
                   :headers #js {"Authorization" (str "Bearer " (-> config :slack :bot-user-oauth-token))}})
      (.then callback-fn)))

(comment
 (get! :get "users.list"
        ;; FIXME detect and log errors
        (fn [rsp] (println rsp)))
 )
