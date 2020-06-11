(ns org.sparkboard.server.slack.core
  (:require [clj-http.client :as client]
            [jsonista.core :as json]
            [lambdaisland.uri]
            [org.sparkboard.js-convert :refer [json->clj]]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [taoensso.timbre :as log])
  (:import [java.net.http HttpClient HttpRequest
                          HttpClient$Version
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]
           [java.net URI]))

(def ^:dynamic *request* {})

(def ^:dynamic *ts* nil)

(def base-uri "https://slack.com/api/")

(defonce ^{:doc "Slack Web API RPC specification"
           :lookup-ts (java.time.LocalDateTime/now (java.time.ZoneId/of "UTC"))}
         web-api-spec
         (delay
           (json/read-value (slurp ; canonical URL per https://api.slack.com/web#basics#spec
                              "https://api.slack.com/specs/openapi/v2/slack_web.json"))))

;; TODO consider https://github.com/gnarroway/hato
;; TODO consider wrapping Java11+ API further
(defn web-api-get
  ;; do we want a 2-arity without `query-map`?
  [family-method config query-map]
  (log/debug "[web-api-get] query-map:" query-map)
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-uri family-method
                                           (when query-map
                                             (str "?" (lambdaisland.uri/map->query-string query-map))))))
                    (.header "Content-Type" "application/x-www-form-urlencoded")
                    (.header "Authorization" (str "Bearer " (:auth/token config)))
                    (.GET)
                    (.build))
        clnt (-> (HttpClient/newBuilder)
                 (.version HttpClient$Version/HTTP_2)
                 (.build))
        rsp (json->clj (.body (.send clnt request (HttpResponse$BodyHandlers/ofString))))]
    (log/info "[web-api-get] ts" (- (System/currentTimeMillis) (or *ts* 0)))
    (log/debug "[web-api] GET rsp:" rsp)
    (when-not (:ok rsp)
      (log/error (-> rsp :response_metadata :messages))
      (throw (ex-info (str "web-api failure: slack/" family-method) {:rsp rsp :config config :query-map query-map})))
    rsp))

(defn web-api-post
  [family-method config body]
  (log/debug "[web-api-post] body:" body)
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create (str base-uri family-method)))
                    (.header "Content-Type" "application/json; charset=utf-8")
                    (.header "Authorization" (str "Bearer " (:auth/token config)))
                    (.POST (HttpRequest$BodyPublishers/ofString (json/write-value-as-string body)))
                    (.build))
        clnt (-> (HttpClient/newBuilder)
                 (.version HttpClient$Version/HTTP_2)
                 (.build))
        rsp (json->clj (.body (.send clnt request (HttpResponse$BodyHandlers/ofString))))]
    (log/info "[web-api-post] ts" (- (System/currentTimeMillis) (or *ts* 0)))
    (when-not (:ok rsp)
      (log/error (-> rsp :response_metadata :messages))
      (throw (ex-info (str "web-api failure: slack/" family-method) {:rsp rsp :config config :body body})))
    (log/debug "[web-api] POST rsp:" rsp)
    rsp))


(defn http-verb [family-method]
  (case (ffirst (get-in @web-api-spec
                        ["paths" (if-not (#{\/} (first family-method))
                                   (str "/" family-method)
                                   family-method)]))
    "get" web-api-get
    "post" web-api-post))

(defn web-api
  ;; We use java HTTP client because `clj-http` fails to properly pass
  ;; JSON bodies - it does some unwanted magic internally.
  ([family-method params]
   (web-api family-method {:auth/token (:slack/bot-token *request*)} params))
  ([family-method config params]
   ((http-verb family-method) family-method config params)))

(def channel-info
  (memoize
    (fn [token channel-id]
      (-> (web-api "conversations.info" {:auth/token token} {:channel channel-id})
          :channel))))

(def team-info
  (memoize
    (fn [token team-id]
      (-> (web-api "team.info" {:auth/token token} {:team team-id})
          :team))))

(defn user-info [token user-id]  
  (:user (web-api "users.info" {:auth/token token} {:user user-id})))

;;;;;;;;;;;;;;;;;;;;;
;; views endpoints, wrwapped to include hash + trigger_id from context

(defn- views
  ([options blocks]
   (web-api (str "views." (:action options))
            (merge {:view (hiccup/->blocks-json blocks)}
                   (some->> (:slack/trigger-id *request*)
                            (hash-map :trigger_id))
                   (when (:view_id options)
                     {:view_id (:view_id options)
                      :hash (:slack/view-hash *request*)})))))

(defn views-open [blocks]
  (views {:action "open"} blocks))
(defn views-push [blocks]
  (views {:action "push"} blocks))
(defn views-update [view-id blocks]
  (views {:action "update"
          :view_id view-id} blocks))

(comment
  (http-verb "users.list")

  (http-verb "conversations.info")                          ; #function[org.sparkboard.server.slack.core/web-api-get]

  (http-verb "channels.list")

  (http-verb "views.publish")                               ;; XXX this seems to be a mistake on Slack's part: it's GET in the spec but POST in the docs

  ;; bot token only for local dev experiments
  (web-api-get "conversations.info" {:auth/token (-> env/config :slack :bot-user-oauth-token)} {:a "b"})
  
  (time (-> (client/get (str base-uri "conversations.info")
                        {:query-params {:token (-> env/config :slack :bot-user-oauth-token)
                                        :channel "C014Y501S2G"}})
            :body
            json/read-value))

  (time (channel-info (-> env/config :slack :bot-user-oauth-token) "C0121SEV6Q2"))

  )

