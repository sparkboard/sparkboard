(ns server.main
  "AWS Lambda <--> Slack API

  Original template from https://github.com/minimal-xyz/minimal-shadow-cljs-nodejs"
  (:require [applied-science.js-interop :as j]
            [server.slack :as slack]
            [shadow.resource :as rc]))

(println "Running ClojureScript in AWS Lambda")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Environment variables/config
(def config (cljs.reader/read-string
              (or (j/get js/process.env :SPARKBOARD_CONFIG)
                  (rc/inline "/.local.config.edn"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Pseudo-database
(defonce db (atom {:slack/users nil
                   :sparkboard/users nil}))

(slack/get! "users.list"
            ;; FIXME detect and log errors
            (fn [rsp] (swap! db #(assoc % :slack/users-raw (js->clj (j/get rsp :members))))))

(slack/get! "channels.list"
            ;; FIXME detect and log errors
            (fn [rsp] (swap! db #(assoc % :slack/channels-raw (js->clj (j/get rsp :channels)
                                                                       :keywordize-keys true)))))

(comment
  (reset! db {:slack/users nil
              :sparkboard/users nil})

  (:slack/users-raw @db)

  (map :name_normalized (:slack/channels-raw @db))
  (map :id (:slack/channels-raw @db))

  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Working with Slack data/requests

(defn from-slack? [event]                                   ;; FIXME ran into trouble with
  ;; goog.crypt.Sha256 so using a hack for
  ;; now. TODO implement HMAC check per
  ;; https://api.slack.com/authentication/verifying-requests-from-slack
  (= (j/get-in event [:headers :user-agent])
     "Slackbot 1.0 (+https://api.slack.com/robots)")
  ;; TODO
  ;; 1. Check X-Slack-Signature HTTP header
  #_(let [s (string/join ":" [(j/get evt :version)
                              (j/get-in evt [:headers :x-slack-request-timestamp])
                              (j/get evt :body)])]))

(defn slack-user [slack-user-id]
  (let [users-by-id (:slack/users-by-id
                      (swap! db #(assoc % :slack/users-by-id
                                        (group-by (fn [usr] (get usr "id"))
                                                  (:slack/users-raw @db)))))]
    (first (get users-by-id slack-user-id))))

(comment
  (slack-user "U012E480NTB")

  )

(defn sparkboard-admin? [slack-username]                    ;; FIXME
  true)

(def project-channel-names                                  ;; FIXME
  (map :name_normalized (:slack/channels-raw @db)))

(defn send-slack-msg! [msg channel-id]
  (slack/post-query-string!
    "chat.postMessage"
    {;;:token (-> config :slack :bot-user-oauth-token) ;; why is this in the headers *and* here?
     :channel channel-id                                    ;; can also be name?
     ;; :blocks TODO
     :text msg}
    (fn [rsp] (println rsp))))

(defn request-updates! [admin-username]
  (run! (partial send-slack-msg! (str admin-username " asks you to please post a project update in #foo-channel"))
        (map :id (:slack/channels-raw @db))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; SparkBoard SlackBot server

(defn response [cb body]
  (cb nil #js {:statusCode 200 :body body}))

(defn handler [event _context callback]
  ;; https://docs.aws.amazon.com/lambda/latest/dg/nodejs-handler.html
  (println "event:" event)
  (println "event->headers" (j/get event :headers))
  (println "event->headers->user-agent:" (j/get-in event [:headers :user-agent]))
  (cond
    ;; Slack API challenge check
    (some #{"challenge"} (js-keys (.parse js/JSON (j/get event :body))))
    (response callback (j/get (.parse js/JSON (j/get event :body)) :challenge))

    :else                                                   ;; FIXME
    (let [admin-user (-> (.parse js/JSON (j/get event :body))
                         (j/get-in [:event :user])
                         slack-user
                         (get "name"))]
      (request-updates! admin-user)
      (response callback (clj->js {:action "broadcast update request to project channels"
                                   :channels project-channel-names})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(comment
  (def dummy-event
    #js {:version "2.0", :routeKey "ANY /slackBot", :rawPath "/default/slackBot", :rawQueryString "", :headers #js {:accept "*/*", :accept-encoding "gzip,deflate", :content-length 604, :content-type "application/json", :host "4jmgrrysk7.execute-api.eu-central-1.amazonaws.com", :user-agent "Slackbot 1.0 (+https://api.slack.com/robots)", :x-amzn-trace-id "Root=1-5eb04251-dff473b7a8d14a51a0dca648", :x-forwarded-for "54.174.192.196", :x-forwarded-port 443, :x-forwarded-proto "https", :x-slack-request-timestamp 1588609617, :x-slack-signature "v0=d846b125b481013842b7e460145f564291455d79ba302acc17bdcad61b762b02"}, :requestContext #js {:accountId 579644408564, :apiId "4jmgrrysk7", :domainName "4jmgrrysk7.execute-api.eu-central-1.amazonaws.com", :domainPrefix "4jmgrrysk7", :http #js {:method "POST", :path "/default/slackBot", :protocol "HTTP/1.1", :sourceIp "54.174.192.196", :userAgent "Slackbot 1.0 (+https://api.slack.com/robots)"}, :requestId "MA9MyiRtFiAEJPQ=", :routeKey "ANY /slackBot", :stage "default", :time "04/May/2020:16:26:57 +0000", :timeEpoch 1588609617791},
         :body "{\"token\":\"abcdefsNabxffKscvxK3nBwh\",\"team_id\":\"T010MGVT4TV\",\"api_app_id\":\"A010P0KP6SV\",\"event\":{\"client_msg_id\":\"756c2148-b72c-45fc-b33d-50309a2b9f49\",\"type\":\"app_mention\",\"text\":\"<@U010MH3GSKD> test\",\"user\":\"U012E480NTB\",\"ts\":\"1588609616.000200\",\"team\":\"T010MGVT4TV\",\"blocks\":[{\"type\":\"rich_text\",\"block_id\":\"rGa\",\"elements\":[{\"type\":\"rich_text_section\",\"elements\":[{\"type\":\"user\",\"user_id\":\"U010MH3GSKD\"},{\"type\":\"text\",\"text\":\" test\"}]}]}],\"channel\":\"C0121SEV6Q2\",\"event_ts\":\"1588609616.000200\"},\"type\":\"event_callback\",\"event_id\":\"Ev0130HRS518\",\"event_time\":1588609616,\"authed_users\":[\"U010MH3GSKD\"]}", :isBase64Encoded false})

  (from-slack? dummy-event)                                 ;; true



  (js->clj (.parse js/JSON (j/get dummy-event :body))
           :keywordize-keys true)
  {:api_app_id "A010P0KP6SV",
   :authed_users ["U010MH3GSKD"]
   :event_id "Ev0130HRS518",
   :event_time 1588609616,
   :team_id "T010MGVT4TV",
   :token "abcdefbxffKscvxK3nBwh",
   :type "event_callback",
   :event {:event_ts "1588609616.000200",
           :channel "C0121SEV6Q2",
           :type "app_mention",
           :ts "1588609616.000200",
           :team "T010MGVT4TV",
           :client_msg_id "756c2148-b72c-45fc-b33d-50309a2b9f49",
           :blocks [{:type "rich_text",
                     :block_id "rGa",
                     :elements [{:type "rich_text_section",
                                 :elements [{:type "user",
                                             :user_id "U010MH3GSKD"}
                                            {:type "text",
                                             :text " test"}]}]}],
           :user "U012E480NTB",
           :text "<@U010MH3GSKD> test"}}

  (j/get-in dummy-event [:headers :user-agent])

  )
