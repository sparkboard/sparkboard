(ns org.sparkboard.slack.api
  (:require [clj-http.client :as client]
            [jsonista.core :as json]
            [lambdaisland.uri]
            [org.sparkboard.js-convert :refer [json->clj]]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.slack.hiccup :as hiccup]
            [taoensso.timbre :as log]
            [org.sparkboard.util :as u])
  (:import [java.net.http HttpClient HttpRequest
                          HttpClient$Version
                          HttpRequest$BodyPublishers
                          HttpResponse$BodyHandlers]
           [java.net URI]))

(def ^:dynamic *context* {})

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
  (log/debug "[web-api-get] query-map:" family-method query-map)
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
   (web-api family-method {:auth/token (:slack/bot-token *context*)} params))
  ([family-method config params]
   ((http-verb family-method) family-method config (u/update-some params {:blocks hiccup/blocks-json
                                                                          :view hiccup/blocks-json}))))

(def channel-info
  (memoize
    (fn channel-info
      ([channel-id] (channel-info (:slack/bot-token *context*) channel-id))
      ([token channel-id]
       (-> (web-api "conversations.info" {:auth/token token} {:channel channel-id})
           :channel)))))

(def team-info
  (memoize
    (fn team-info
      ([team-id] (team-info (:slack/bot-token *context*) team-id))
      ([token team-id]
       (-> (web-api "team.info" {:auth/token token} {:team team-id})
           :team)))))

(defn user-info [token user-id]
  (:user (web-api "users.info" {:auth/token token} {:user user-id})))

(defn message-user! [context blocks]
  (web-api "chat.postMessage"
           {:auth/token (:slack/bot-token context)}
           {:channel (:slack/user-id context)
            :blocks blocks}))

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

