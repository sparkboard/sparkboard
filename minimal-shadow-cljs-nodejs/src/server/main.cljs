(ns server.main
  "AWS Lambda <--> Slack API

  Original template from https://github.com/minimal-xyz/minimal-shadow-cljs-nodejs"
  (:require [applied-science.js-interop :as j]
            [clojure.string :as string]
            [kitchen-async.promise :as p]
            [lambdaisland.uri :as uri]
            [server.common :refer [clj->json decode-base64 parse-json json->clj]]
            [server.slack :as slack]
            [cljs.pprint :as pp]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Pseudo-database
(defonce db (atom {:slack/users nil
                   :sparkboard/users nil}))

(comment
  ;; these requests don't block

  (p/let [rsp (slack/get+ "users.list")]
    ;; FIXME detect and log errors
    (swap! db #(assoc % :slack/users-raw (js->clj (j/get rsp :members)))))

  (p/let [rsp (slack/get+ "channels.list")]
    ;; FIXME detect and log errors
    (swap! db #(assoc % :slack/channels-raw (js->clj (j/get rsp :channels)
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

(defn send-slack-blocks+ [blocks channel]
  (p/->> (slack/post-query-string+ "chat.postMessage"
                                   {:channel channel        ;; id or name
                                    :blocks (clj->json blocks)})
         ;; TODO better callback
         (println "slack blocks response:")))

(defn send-slack-msg+ [msg channel]
  (slack/post-query-string+ "chat.postMessage"
                            {:channel channel               ;; id or name
                             :text msg}))

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
   {:type "section", :block_id "sb-section1"
    :text {:type "mrkdwn", :text "*Post responses to channel:*"},
    :accessory {:type "conversations_select",
                :placeholder {:type "plain_text",
                              :text "Select a channel...",
                              :emoji true},
                :action_id "broadcast2:channel-select"
                :filter {:include ["public" "private"]}}}
   {:type "input", :block_id "sb-input1"
    :element {:type "plain_text_input",
              :multiline true,
              :action_id "broadcast2:text-input"
              :initial_value "It's 2 o'clock! Please post a brief update of your team's progress so far today."},
    :label {:type "plain_text", :text "Message:", :emoji true}}])

(defn modal-view-payload [title blocks]
  {:type :modal
   :title {:type "plain_text"
           :text title}
   :blocks blocks})

(defn request-updates! [msg channels]
  ;; TODO
  ;; Write broadcast to Firebase
  (p/all
    (map (partial send-slack-msg+ msg) channels)))

(defn decode-text-input [s]
  ;; Slack appears to use some (?) of
  ;; `application/x-www-form-urlencoded` for at least multiline text
  ;; input, specifically replacing spaces with `+`
  (string/replace s "+" " "))

(comment
  (parse-json (clj->json (modal-view-payload "Broadcast" blocks-broadcast-1)))

  )

(defn handle-modal! [{payload-type :type
                      :keys [trigger_id]
                      [{:keys [action_id]}] :actions
                      {:keys [view_id]} :container
                      :as payload}]
  (case payload-type
    "shortcut"                                              ; Slack "Global shortcut".
    ;; Show initial modal of action options (currently just Compose button).
    (do (println "[handle-modal]/shortcut; blocks:" (modal-view-payload "Broadcast" blocks-broadcast-1))
        (slack/views-open! trigger_id (modal-view-payload "Broadcast" blocks-broadcast-1)))

    "block_actions"                                         ; User acted on existing modal
    ;; Branch on specifics of given action
    (case action_id
      "broadcast1:compose"
      (slack/views-update! view_id (assoc (modal-view-payload "Compose Broadcast" blocks-broadcast-2)
                                          :submit {:type "plain_text",
                                                   :text "Submit"}))

      ;; TODO FIXME
      #_"broadcast2:channel-select"
      #_(slack/views-push! (j/get-in payload ["container" "view_id"])
                           (assoc (modal-view-payload "Compose Broadcast" blocks-broadcast-2)
                                  :submit {:type "plain_text", :text "Submit"})))

    "view_submission"                                       ; "Submit" button pressed
    ;; In the future we will need to branch on other data
    (p/let [channel-ids (p/-> (slack/get+ "channels.list")
                              (j/get :channels)
                              (->> (keep (j/fn [^:js {:keys [is_member id]}]
                                           ;; TODO
                                           ;; ensure bot joins team-channels when they are created
                                           (when is_member id)))))
            message-text (decode-text-input (get-in payload [:view
                                                             :state
                                                             :values
                                                             :sb-input1
                                                             :broadcast2:text-input
                                                             :value]))]
      (request-updates! message-text channel-ids))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; SparkBoard SlackBot server

(defn response [body]
  (p/let [body-resolved body]
    (println :responding-with body-resolved)
    (p/resolve #js {:statusCode 200 :body (clj->js body-resolved)})))

(j/defn parse-body [^:js {:as event
                          :keys [body]
                          {:keys [Content-Type]} :headers}]
  (case Content-Type
    "application/x-www-form-urlencoded" (uri/query-string->map (do #_decode-base64 body))
    "application/json" (js->clj (js/JSON.parse body) :keywordize-keys true)
    body))

(defn handler [event _context]
  "Main AWS Lambda handler. Invoked by slackBot.
   See https://docs.aws.amazon.com/lambda/latest/dg/nodejs-handler.html"
  (let [body (parse-body event)
        request-type (cond (:challenge body) :challenge
                           (:event body) :event
                           (:payload body) :interaction)]
    (pp/pprint ["[handler] request-type:" request-type])
    (pp/pprint ["[handler] body:" body])
    ;(pp/pprint ["[handler] event:" event])

    (response
      (case request-type
        ;; Slack API: identification challenge
        :challenge
        (:challenge body)

        ;; Slack Interaction (e.g. global shortcut)
        :interaction
        (p/let [action-result (handle-modal! (json->clj (:payload body)))]
          (println :action-result action-result)
          nil)

        ;; Slack Event
        :event (prn "NOT IMPLEMENTED")

        {:action "broadcast update request to project channels"
         :channels (request-updates! (-> (get-in body [:event :user])
                                         slack-user
                                         (get "name"))
                                     (map :id (:slack/channels-raw @db)))}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(comment
  (parse-json (:payload (uri/query-string->map (decode-base64 "cGF5bG9hZD0lN0IlMjJ0eXBlJTIyJTNBJTIyc2hvcnRjdXQlMjIlMkMlMjJ0b2tlbiUyMiUzQSUyMjJHSTVkSHNOYWJ4ZmZLc2N2eEszbkJ3aCUyMiUyQyUyMmFjdGlvbl90cyUyMiUzQSUyMjE1ODk1NTI5NDEuMjQ0ODQ4JTIyJTJDJTIydGVhbSUyMiUzQSU3QiUyMmlkJTIyJTNBJTIyVDAxME1HVlQ0VFYlMjIlMkMlMjJkb21haW4lMjIlM0ElMjJzcGFya2JvYXJkLWFwcCUyMiU3RCUyQyUyMnVzZXIlMjIlM0ElN0IlMjJpZCUyMiUzQSUyMlUwMTJFNDgwTlRCJTIyJTJDJTIydXNlcm5hbWUlMjIlM0ElMjJkYXZlLmxpZXBtYW5uJTIyJTJDJTIydGVhbV9pZCUyMiUzQSUyMlQwMTBNR1ZUNFRWJTIyJTdEJTJDJTIyY2FsbGJhY2tfaWQlMjIlM0ElMjJzcGFya2JvYXJkJTIyJTJDJTIydHJpZ2dlcl9pZCUyMiUzQSUyMjExMzk0Mzg2ODYzMDUuMTAyMTU3MzkyMjk0Ny5kNDhhNDExNDBmMjJhMjc4YTlmNGUxZTVkNDliMDBjYSUyMiU3RA=="))))
  )
