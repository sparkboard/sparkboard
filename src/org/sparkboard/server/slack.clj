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

(defn create-linked-channel! [channel-name]
  ;; TODO determine channel naming scheme
  (let [create-rsp (create-channel! channel-name)
        chnnl-id (-> create-rsp :body
                     (json/read-value (json/object-mapper {:decode-key-fn keyword}))
                     :channel :id)]
    ;; TODO save channel name to Sparkboard DB
    ;; Add members of the Sparkboard project to the channel
    (invite-to-channel! channel-name ;; TODO user name lookup
                        ["FIXME"])
    ;; "Pin a message to the top of the channel, TODO linking back to
    ;; the Sparkboard project"
    (let [msg-rsp (-> (web-api "/chat.postMessage"
                               {:channel chnnl-id :text "FIXME"}
                               (json/object-mapper {:decode-key-fn keyword}))
                      :body
                      (json/read-value (json/object-mapper {:decode-key-fn keyword})))]
      (if (:ok msg-rsp)
        (web-api "/pins.add" {:channel chnnl-id
                              :timestamp (:ts msg-rsp)})))
    (web-api "/conversations.setPurpose" {:purpose "FIXME (purpose)" :channel chnnl-id})
    (web-api "/conversations.setTopic" {:topic "FIXME (topic)" :channel chnnl-id})))

;; TODO "when a new member joins a project, add them to the linked channel"

(comment ;; Flow: new linked channel is created
  ;; Create channel
  (create-channel! "is-this-thing-on") ;; TODO determine channel naming scheme

  ;; "Add existing members of the project to the channel"
  (invite-to-channel! "is-this-thing-on"
                      ;; TODO user name lookup
                      ["dave.liepmann"])
  
  ;; "Pin a message to the top of the channel, TODO linking back to the
  ;; Sparkboard project"  
  (let [chnnl (channel-id "is-this-thing-on")
        msg-rsp (json/read-value (:body (web-api "/chat.postMessage"
                                                 {:channel chnnl :text "FIXME"}))
                                 (json/object-mapper {:decode-key-fn keyword}))]
    (if (:ok msg-rsp)
      (web-api "/pins.add" {:channel chnnl
                            :timestamp (:ts msg-rsp)})))

  ;; Set channel purpose/description
  (web-api "/conversations.setPurpose" {:purpose "FIXME (purpose)"
                                        :channel (channel-id "is-this-thing-on")})

  ;; Set channel topic
  (web-api "/conversations.setTopic" {:topic "FIXME (topic)"
                                      :channel (channel-id "is-this-thing-on")})
  
  ;; TODO "Store the channel ID with the project"
  
  )

(comment ;;;; Feature: "prompted updates" from teams
  ;; TODO `broadcast` function, for organizers to ping all active project channels.
  (doseq [cn (map :get-channel-name-FIXME [:FIXME :sparkboard-database-lookup])]
    (web-api "/chat.postMessage"
             {:channel (channel-id cn) :text "FIXME"}
             (json/object-mapper {:decode-key-fn keyword})))

  ;; TODO determine what info we want to save on Sparkboard side, and how configurable we want that to be
  ;; TODO use that to decide interaction method
  ;; ....  - block kit https://api.slack.com/block-kit/interactivity
  ;; ....  - shortcuts or slash commands https://api.slack.com/interactivity/entry-points
  
  )

(comment
  (http-verb "/users.list")
  
  (map :name (users))
  ;; ("slackbot" "me1" "sparkboard" "mhuebert" "dave.liepmann")

  (channels)

  ;; TODO delete/archive channel, for testing and clean-up
  )
