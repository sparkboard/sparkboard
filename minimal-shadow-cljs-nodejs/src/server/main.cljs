(ns server.main
  "AWS Lambda <--> Slack API

  Original template from https://github.com/minimal-xyz/minimal-shadow-cljs-nodejs"
  (:require [applied-science.js-interop :as j]
            [lambdaisland.uri :as uri]
            [server.common :refer [parse-json decode-base64]]
            [server.slack :as slack]))

(println "Running ClojureScript in AWS Lambda")

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

(defn from-slack? [event] ;; FIXME ran into trouble with
  ;; goog.crypt.Sha256 so using a hack for
  ;; now. TODO implement HMAC check per
  ;; https://api.slack.com/authentication/verifying-requests-from-slack
  (= (j/get-in event [:headers :user-agent])
     "Slackbot 1.0 (+https://api.slack.com/robots)")
  ;; TODO
  ;; 1. Check X-Slack-Signature HTTP header
  #_  (let [s (string/join ":" [(j/get evt :version)
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

(defn sparkboard-admin? [slack-username] ;; FIXME
  true)

(def project-channel-names ;; FIXME
  (map :name_normalized (:slack/channels-raw @db)))

(defn clj->json [x]
  (.stringify js/JSON (clj->js x)))

(defn send-slack-modal! [trigger-id blocks]
  (slack/post-query-string! "views.open"
                            {:trigger_id trigger-id
                             :view (clj->json blocks)}
                            ;; TODO better callback
                            (fn [rsp] (println "slack modal response:" rsp))))

(defn send-slack-blocks! [blocks channel]
  (slack/post-query-string! "chat.postMessage"
                            {:channel channel ;; id or name
                             :blocks (clj->json blocks)}
                            ;; TODO better callback
                            (fn [rsp] (println "slack blocks response:" rsp))))

(defn send-slack-msg! [msg channel]
  (slack/post-query-string! "chat.postMessage"
                            {:channel channel ;; id or name
                             :text msg}
                            ;; TODO better callback
                            (fn [rsp] (println "slack msg response:" rsp))))

(def blocks-broadcast-1
  [{:type "divider"}
   {:type "section",
    :text {:type "mrkdwn",
           :text "*Team Broadcast*\nSend a message to all teams."},
    :accessory {:type "button",
                :text {:type "plain_text", :text "Compose", :emoji true},
                :style "primary",
                :action_id "broadcast1:compose"
                :value "click_me_123"}}])

(def blocks-broadcast-2
  [{:type "section",
    :text {:type "mrkdwn", :text "Send a prompt to *all projects*."}}
   {:type "divider"}
   {:type "section",
    :text {:type "mrkdwn", :text "*Post responses to channel:*"},
    :accessory {:type "conversations_select",
                :placeholder {:type "plain_text",
                              :text "Select a channel...",
                              :emoji true},
                :filter {:include ["public" "private"]}}}
   {:type "input",
    :element {:type "plain_text_input",
              :multiline true,
              :initial_value "It's 2 o'clock! Please post a brief update of your team's progress so far today."},
    :label {:type "plain_text", :text "Message:", :emoji true}}])

(defn modal-view-payload [title blocks]
  {:type :modal
   :title {:type "plain_text"
           :text title}
   :blocks blocks})

(defn request-updates! [admin-username channels]
  ;; Write broadcast to Firebase
  
  ;; Send message to Slack channels
  (run! (partial send-slack-msg! (str admin-username " asks you to please post a project update in #foo-channel"))
        channels)
  ;; Return channels (?)
  channels)

(defn handle-modal! [payload]
  (case (j/get payload "type")
    "shortcut" ; Slack "Global shortcut". Show initial modal of action
                                        ; options (currently just Compose button).
    (send-slack-modal! (j/get payload "trigger_id")
                       (modal-view-payload "Broadcast" blocks-broadcast-1))

    "block_actions" ; branch on user action within prior modal
    (case (-> payload (j/get "actions") first (j/get "action_id"))
      "broadcast1:compose"
      (send-slack-modal! (j/get payload "trigger_id")
                         (modal-view-payload "Compose Broadcast" blocks-broadcast-2)))))


(comment
  (-> (parse-json (:payload (uri/query-string->map (decode-base64 "cGF5bG9hZD0lN0IlMjJ0eXBlJTIyJTNBJTIyYmxvY2tfYWN0aW9ucyUyMiUyQyUyMnVzZXIlMjIlM0ElN0IlMjJpZCUyMiUzQSUyMlUwMTJFNDgwTlRCJTIyJTJDJTIydXNlcm5hbWUlMjIlM0ElMjJkYXZlLmxpZXBtYW5uJTIyJTJDJTIybmFtZSUyMiUzQSUyMmRhdmUubGllcG1hbm4lMjIlMkMlMjJ0ZWFtX2lkJTIyJTNBJTIyVDAxME1HVlQ0VFYlMjIlN0QlMkMlMjJhcGlfYXBwX2lkJTIyJTNBJTIyQTAxMFAwS1A2U1YlMjIlMkMlMjJ0b2tlbiUyMiUzQSUyMjJHSTVkSHNOYWJ4ZmZLc2N2eEszbkJ3aCUyMiUyQyUyMmNvbnRhaW5lciUyMiUzQSU3QiUyMnR5cGUlMjIlM0ElMjJ2aWV3JTIyJTJDJTIydmlld19pZCUyMiUzQSUyMlYwMTRDTlVSVUZKJTIyJTdEJTJDJTIydHJpZ2dlcl9pZCUyMiUzQSUyMjExMTAxOTgwMTM0MzEuMTAyMTU3MzkyMjk0Ny5jOTA1MWYzOTM5ZDFkMDU4ZGRiODk1MDUzZjAwNDU2ZiUyMiUyQyUyMnRlYW0lMjIlM0ElN0IlMjJpZCUyMiUzQSUyMlQwMTBNR1ZUNFRWJTIyJTJDJTIyZG9tYWluJTIyJTNBJTIyc3Bhcmtib2FyZC1hcHAlMjIlN0QlMkMlMjJ2aWV3JTIyJTNBJTdCJTIyaWQlMjIlM0ElMjJWMDE0Q05VUlVGSiUyMiUyQyUyMnRlYW1faWQlMjIlM0ElMjJUMDEwTUdWVDRUViUyMiUyQyUyMnR5cGUlMjIlM0ElMjJtb2RhbCUyMiUyQyUyMmJsb2NrcyUyMiUzQSU1QiU3QiUyMnR5cGUlMjIlM0ElMjJkaXZpZGVyJTIyJTJDJTIyYmxvY2tfaWQlMjIlM0ElMjJyQVlXJTIyJTdEJTJDJTdCJTIydHlwZSUyMiUzQSUyMnNlY3Rpb24lMjIlMkMlMjJibG9ja19pZCUyMiUzQSUyMlNkdXF0JTIyJTJDJTIydGV4dCUyMiUzQSU3QiUyMnR5cGUlMjIlM0ElMjJtcmtkd24lMjIlMkMlMjJ0ZXh0JTIyJTNBJTIyJTJBVGVhbStCcm9hZGNhc3QlMkElNUNuU2VuZCthK21lc3NhZ2UrdG8rYWxsK3RlYW1zLiUyMiUyQyUyMnZlcmJhdGltJTIyJTNBZmFsc2UlN0QlMkMlMjJhY2Nlc3NvcnklMjIlM0ElN0IlMjJ0eXBlJTIyJTNBJTIyYnV0dG9uJTIyJTJDJTIyYWN0aW9uX2lkJTIyJTNBJTIyYnJvYWRjYXN0MSUzQWNvbXBvc2UlMjIlMkMlMjJzdHlsZSUyMiUzQSUyMnByaW1hcnklMjIlMkMlMjJ0ZXh0JTIyJTNBJTdCJTIydHlwZSUyMiUzQSUyMnBsYWluX3RleHQlMjIlMkMlMjJ0ZXh0JTIyJTNBJTIyQ29tcG9zZSUyMiUyQyUyMmVtb2ppJTIyJTNBdHJ1ZSU3RCUyQyUyMnZhbHVlJTIyJTNBJTIyY2xpY2tfbWVfMTIzJTIyJTdEJTdEJTVEJTJDJTIycHJpdmF0ZV9tZXRhZGF0YSUyMiUzQSUyMiUyMiUyQyUyMmNhbGxiYWNrX2lkJTIyJTNBJTIyJTIyJTJDJTIyc3RhdGUlMjIlM0ElN0IlMjJ2YWx1ZXMlMjIlM0ElN0IlN0QlN0QlMkMlMjJoYXNoJTIyJTNBJTIyMTU4OTQ4MjUwOC4xMTUwYzBlOCUyMiUyQyUyMnRpdGxlJTIyJTNBJTdCJTIydHlwZSUyMiUzQSUyMnBsYWluX3RleHQlMjIlMkMlMjJ0ZXh0JTIyJTNBJTIyQnJvYWRjYXN0JTIyJTJDJTIyZW1vamklMjIlM0F0cnVlJTdEJTJDJTIyY2xlYXJfb25fY2xvc2UlMjIlM0FmYWxzZSUyQyUyMm5vdGlmeV9vbl9jbG9zZSUyMiUzQWZhbHNlJTJDJTIyY2xvc2UlMjIlM0FudWxsJTJDJTIyc3VibWl0JTIyJTNBbnVsbCUyQyUyMnByZXZpb3VzX3ZpZXdfaWQlMjIlM0FudWxsJTJDJTIycm9vdF92aWV3X2lkJTIyJTNBJTIyVjAxNENOVVJVRkolMjIlMkMlMjJhcHBfaWQlMjIlM0ElMjJBMDEwUDBLUDZTViUyMiUyQyUyMmV4dGVybmFsX2lkJTIyJTNBJTIyJTIyJTJDJTIyYXBwX2luc3RhbGxlZF90ZWFtX2lkJTIyJTNBJTIyVDAxME1HVlQ0VFYlMjIlMkMlMjJib3RfaWQlMjIlM0ElMjJCMDEwWjFKOEJSNiUyMiU3RCUyQyUyMmFjdGlvbnMlMjIlM0ElNUIlN0IlMjJhY3Rpb25faWQlMjIlM0ElMjJicm9hZGNhc3QxJTNBY29tcG9zZSUyMiUyQyUyMmJsb2NrX2lkJTIyJTNBJTIyU2R1cXQlMjIlMkMlMjJ0ZXh0JTIyJTNBJTdCJTIydHlwZSUyMiUzQSUyMnBsYWluX3RleHQlMjIlMkMlMjJ0ZXh0JTIyJTNBJTIyQ29tcG9zZSUyMiUyQyUyMmVtb2ppJTIyJTNBdHJ1ZSU3RCUyQyUyMnZhbHVlJTIyJTNBJTIyY2xpY2tfbWVfMTIzJTIyJTJDJTIyc3R5bGUlMjIlM0ElMjJwcmltYXJ5JTIyJTJDJTIydHlwZSUyMiUzQSUyMmJ1dHRvbiUyMiUyQyUyMmFjdGlvbl90cyUyMiUzQSUyMjE1ODk0ODI1MTAuOTQ2Njc2JTIyJTdEJTVEJTdE")))
                  :keywordize-keys true) (j/get "actions") first (j/get "action_id"))
  

)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; SparkBoard SlackBot server

(defn response [cb body]
  (cb nil #js {:statusCode 200 :body body}))

(defn handler [event _context callback]
  "Main AWS Lambda handler. Invoked by slackBot.
   See https://docs.aws.amazon.com/lambda/latest/dg/nodejs-handler.html"
  (println "event:" event)
  (let [body (j/get event :body)]
    (cond
      ;; Slack API: identification challenge
      (some #{"challenge"} (js-keys (parse-json body)))
      (response callback (j/get (.parse js/JSON (j/get event :body)) :challenge))

      ;; Slack Event triggered (e.g. global shortcut)
      (:payload (uri/query-string->map (decode-base64 body)))
      (handle-modal! (parse-json (:payload (uri/query-string->map (decode-base64 body)))
                                 :keywordize-keys true))
      
      :else
      (response callback (clj->js {:action "broadcast update request to project channels"
                                   :channels (request-updates! (-> (.parse js/JSON body)
                                                                   (j/get-in [:event :user])
                                                                   slack-user
                                                                   (get "name"))
                                                               (map :id (:slack/channels-raw @db)))})))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(comment
  (j/get #js {:type "block_actions",
              :user #js {:id "U012E480NTB", :username "dave.liepmann", :name "dave.liepmann", :team_id "T010MGVT4TV"},
              :api_app_id "A010P0KP6SV", :token "2GI5dHsNabxffKscvxK3nBwh",
              :container #js {:type "view", :view_id "V013NN81CSX"},
              :trigger_id "1109815195271.1021573922947.a0dd388e6b681774be9d14efe602f86f",
              :team #js {:id "T010MGVT4TV", :domain "sparkboard-app"},

              :view #js {:id "V013NN81CSX", :team_id "T010MGVT4TV", :type "modal", :blocks #js [#js {:type "divider", :block_id "LY1"} #js {:type "section", :block_id "YIWrv", :text #js {:type "mrkdwn", :text "*Team+Broadcast*\nSend+a+message+to+all+teams.", :verbatim false}, :accessory #js {:type "button", :action_id "broadcast1:compose", :style "primary", :text #js {:type "plain_text", :text "Compose", :emoji true}, :value "click_me_123"}}], :private_metadata "", :callback_id "", :state #js {:values #js {}}, :hash "1589473604.c3a4512c", :title #js {:type "plain_text", :text "Broadcast", :emoji true}, :clear_on_close false, :notify_on_close false, :close nil, :submit nil, :previous_view_id nil, :root_view_id "V013NN81CSX", :app_id "A010P0KP6SV", :external_id "", :app_installed_team_id "T010MGVT4TV", :bot_id "B010Z1J8BR6"},

              :actions #js [#js {:action_id "broadcast1:compose",
                                 :block_id "YIWrv",
                                 :text #js {:type "plain_text",
                                            :text "Compose",
                                            :emoji true},
                                 :value "click_me_123",
                                 :style "primary",
                                 :type "button",
                                 :action_ts "1589473805.190240"}]} [:actions :action_id])
  
  
  (def dummy-event
    #js {:version "2.0", :routeKey "ANY /slackBot", :rawPath "/default/slackBot", :rawQueryString "", :headers #js {:accept "*/*", :accept-encoding "gzip,deflate", :content-length 604, :content-type "application/json", :host "4jmgrrysk7.execute-api.eu-central-1.amazonaws.com", :user-agent "Slackbot 1.0 (+https://api.slack.com/robots)", :x-amzn-trace-id "Root=1-5eb04251-dff473b7a8d14a51a0dca648", :x-forwarded-for "54.174.192.196", :x-forwarded-port 443, :x-forwarded-proto "https", :x-slack-request-timestamp 1588609617, :x-slack-signature "v0=d846b125b481013842b7e460145f564291455d79ba302acc17bdcad61b762b02"}, :requestContext #js {:accountId 579644408564, :apiId "4jmgrrysk7", :domainName "4jmgrrysk7.execute-api.eu-central-1.amazonaws.com", :domainPrefix "4jmgrrysk7", :http #js {:method "POST", :path "/default/slackBot", :protocol "HTTP/1.1", :sourceIp "54.174.192.196", :userAgent "Slackbot 1.0 (+https://api.slack.com/robots)"}, :requestId "MA9MyiRtFiAEJPQ=", :routeKey "ANY /slackBot", :stage "default", :time "04/May/2020:16:26:57 +0000", :timeEpoch 1588609617791},
         :body "{\"token\":\"abcdefsNabxffKscvxK3nBwh\",\"team_id\":\"T010MGVT4TV\",\"api_app_id\":\"A010P0KP6SV\",\"event\":{\"client_msg_id\":\"756c2148-b72c-45fc-b33d-50309a2b9f49\",\"type\":\"app_mention\",\"text\":\"<@U010MH3GSKD> test\",\"user\":\"U012E480NTB\",\"ts\":\"1588609616.000200\",\"team\":\"T010MGVT4TV\",\"blocks\":[{\"type\":\"rich_text\",\"block_id\":\"rGa\",\"elements\":[{\"type\":\"rich_text_section\",\"elements\":[{\"type\":\"user\",\"user_id\":\"U010MH3GSKD\"},{\"type\":\"text\",\"text\":\" test\"}]}]}],\"channel\":\"C0121SEV6Q2\",\"event_ts\":\"1588609616.000200\"},\"type\":\"event_callback\",\"event_id\":\"Ev0130HRS518\",\"event_time\":1588609616,\"authed_users\":[\"U010MH3GSKD\"]}", :isBase64Encoded false})

  (from-slack? dummy-event) ;; true

  
  
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
