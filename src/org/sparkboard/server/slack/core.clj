(ns org.sparkboard.server.slack.core
  (:require [clj-http.client :as client]
            [jsonista.core :as json]
            [org.sparkboard.env :as env]
            [taoensso.timbre :as log])
  (:import [java.net.http HttpClient HttpRequest HttpClient$Version HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.net URI]))

(defonce ^{:doc "Slack Web API RPC specification"
           :lookup-ts (java.time.LocalDateTime/now (java.time.ZoneId/of "UTC"))}
  web-api-spec
  (json/read-value (slurp ;; canonical URL per https://api.slack.com/web#basics#spec
                    "https://api.slack.com/specs/openapi/v2/slack_web.json")))

(defn http-verb [family-method]
  (case (ffirst (get-in web-api-spec
                        ["paths" (if-not (#{\/} (first family-method))
                                   (str "/" family-method)
                                   family-method)]))
    "get"  client/get
    "post" client/post))

;; TODO consider https://github.com/gnarroway/hato
;; TODO consider wrapping Java11+ API further
(defn web-api
  ;; because `clj-http` fails to properly pass JSON bodies - it does some unwanted magic internally
  ([family-method token]
   (let [request (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str "https://slack.com/api/" family-method)))
                     (.header "Content-Type" "application/json; charset=utf-8")
                     (.header "Authorization" (str "Bearer " token))
                     (.GET)
                     (.build))
         clnt (-> (HttpClient/newBuilder)
                  (.version HttpClient$Version/HTTP_2)
                  (.build))
         rsp (.body (.send clnt request (HttpResponse$BodyHandlers/ofString)))]
     (log/debug "[web-api] GET rsp:" rsp)
     (json/read-value rsp)))
  ([family-method token body]
   (log/debug "[web-api] body:" body)
   (let [request (-> (HttpRequest/newBuilder)
                     (.uri (URI/create (str "https://slack.com/api/" family-method)))
                     (.header "Content-Type" "application/json; charset=utf-8")
                     (.header "Authorization" (str "Bearer " token))
                     (.POST (HttpRequest$BodyPublishers/ofString (json/write-value-as-string body)))
                     (.build))
         clnt (-> (HttpClient/newBuilder)
                  (.version HttpClient$Version/HTTP_2)
                  (.build))
         rsp (.body (.send clnt request (HttpResponse$BodyHandlers/ofString)))]
     (log/debug "[web-api] POST rsp:" rsp)
     (json/read-value rsp))))

;; TODO "when a new member joins a project, add them to the linked channel"

(comment
  (http-verb "/users.list")
  
  ;; TODO delete/archive channel, for testing and clean-up
  )
