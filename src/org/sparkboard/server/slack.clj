(ns org.sparkboard.server.slack
  (:require [clj-http.client :as client]
            [clojure.string :as string]
            [jsonista.core :as json]
            [org.sparkboard.env :as env]))

(def ^{:doc "Slack Web API RPC specification"
       :lookup-ts (java.time.LocalDateTime/now (java.time.ZoneId/of "UTC"))}
  web-api-spec
  (json/read-value (slurp ;; canonical URL per https://api.slack.com/web#basics#spec
                    "https://api.slack.com/specs/openapi/v2/slack_web.json")))

(defn http-verb [method]
  (case (ffirst (get-in web-api-spec ["paths" method]))
    "get"  client/get
    "post" client/post))

(defn web-api
  ([family-method] (web-api family-method nil))
  ([family-method body]
   ((http-verb family-method) (str "https://slack.com/api" family-method)
    {:content-type "application/json; charset=utf-8"
     :headers {"Authorization" (str "Bearer " (-> env/get :slack.app :bot-user-oauth-token))}
     :body (json/write-value-as-string body)})))

(defn invite-to-channel! [channel-name usernames]
  (let [usrs (into {} (map (juxt :name :id)) (users))]
    (web-api "/conversations.invite" {:users (map usrs usernames)
                                      :channel (channel-id channel-name)})))

(defn create-channel! [channel]
  ;; https://api.slack.com/methods/conversations.create
  (web-api "/conversations.create" {:name channel}))

(defn users []
  (:members (json/read-value (:body (web-api "/users.list"))
                             (json/object-mapper {:decode-key-fn keyword}))))

(defn channels []
  (reduce (fn [m channel]
            (assoc m (:name_normalized channel) channel))
          {}
          (:channels (json/read-value (:body (web-api "/channels.list"))
                               (json/object-mapper {:decode-key-fn keyword})))))

(defn channel-id [channel-name]
  (:id (get (channels) channel-name)))


;; TODO automatically add users

(comment
  (http-verb "/users.list")
  
  (create-channel! "is-this-thing-on")

  (map :name (users))
  ;; ("slackbot" "me1" "sparkboard" "mhuebert" "dave.liepmann")

  (channels)

  ;; TODO delete/archive channel, for testing and clean-up
  )
