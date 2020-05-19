(ns server.slack.handlers
  (:require ["aws-sdk" :as aws]
            [applied-science.js-interop :as j]
            [cljs.pprint :as pp]
            [clojure.string :as str]
            [kitchen-async.promise :as p]
            [server.blocks :as blocks]
            [server.http :as http]
            [server.slack :as slack]
            [server.slack.screens :as screens]
            [server.slack.screens :as slack-screens]
            [server.common :as common]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Deferred tasks

(def topic-arn (j/get-in js/process [:env :DEFERRED_TASK_TOPIC_ARN]))
(def SNS (delay (new aws/SNS #js{:apiVersion "2010-03-31"})))

(defn handle-deferred-task [message event context]
  (prn :task-message message))

(defn defer-task!
  "Sends `payload` to `handle-deferred-task` in a newly invoked lambda"
  [payload]
  ;; https://docs.aws.amazon.com/sdk-for-javascript/v2/developer-guide/sns-examples-publishing-messages.html
  (.publish ^js @SNS
            (j/obj :Message (common/clj->json payload)
                   :TopicArn topic-arn)
            (fn [err data]
              (when err
                (js/console.error "error deferring task: " err)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn send-slack-blocks+ [blocks channel]
  (p/->> (slack/post-query-string+ "chat.postMessage"
                                   {:channel channel        ;; id or name
                                    :blocks (blocks/to-json blocks)})
         ;; TODO better callback
         (println "slack blocks response:")))

(defn send-slack-msg+ [msg channel]
  (slack/post-query-string+ "chat.postMessage"
                            {:channel channel               ;; id or name
                             :text msg}))

(defn request-updates! [msg channels]
  ;; TODO
  ;; Write broadcast to Firebase
  (p/->> (map (partial send-slack-msg+ msg) channels)
         (p/all)
         (map http/assert-ok)))

(defn handle-event! [{:as event
                      event-type :type
                      :keys [user channel tab]}]
  (pp/pprint [::event event])
  (defer-task! event)
  (p/-> (case event-type
          "app_home_opened"
          (slack/post-query-string+ "views.publish"
                                    {:user_id user
                                     :view
                                     (blocks/to-json
                                       (screens/home))})
          [:unhandled-event event-type])
        prn))

(defn decode-text-input [s]
  ;; Slack appears to use some (?) of
  ;; `application/x-www-form-urlencoded` for at least multiline text
  ;; input, specifically replacing spaces with `+`
  (str/replace s "+" " "))

(defn handle-interaction! [{payload-type :type
                            :keys [trigger_id]
                            [{:keys [action_id]}] :actions
                            {view-type :type} :view
                            {:as container :keys [view_id]} :container
                            :as payload}]
  (pp/pprint [:interaction-payload container])
  (case payload-type
    "shortcut"                                              ; Slack "Global shortcut".
    ;; Show initial modal of action options (currently just Compose button).
    (do (println "[handle-modal]/shortcut; blocks:" screens/shortcut-modal)
        (slack/views-open! trigger_id screens/shortcut-modal))

    "block_actions"                                         ; User acted on existing modal
    ;; Branch on specifics of given action
    (case action_id
      "admin:team-broadcast"
      (case view-type
        "modal" (slack/views-update! view_id screens/team-broadcast-modal-compose)
        "home" (slack/views-open! trigger_id screens/team-broadcast-modal-compose))

      ;; TODO FIXME
      #_"broadcast2:channel-select"
      #_(slack/views-push! (j/get-in payload ["container" "view_id"])
                           [:modal {:title "Compose Broadcast"
                                    :blocks blocks-broadcast-2
                                    :submit [:plain_text "Submit"]}])
      (println [:unhandled-block-action action_id]))

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
      (request-updates! message-text channel-ids))
    (println [:unhandled-modal payload-type])))