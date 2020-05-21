(ns server.slack.handlers
  (:require [applied-science.js-interop :as j]
            [cljs.pprint :as pp]
            [clojure.string :as str]
            [kitchen-async.promise :as p]
            [server.blocks :as blocks]
            [server.deferred-tasks :as tasks]
            [server.http :as http]
            [server.slack :as slack]
            [server.slack.screens :as screens]))

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
  (p/->> (map (partial send-slack-blocks+ (screens/team-broadcast-message msg)) channels)
         (p/all)
         (map http/assert-ok)))

(tasks/alias! ::request-updates request-updates!)

(defn handle-event! [{:as event
                      event-type :type
                      :keys [user channel tab]}]
  (p/-> (case event-type
          "app_home_opened"
          (tasks/publish! [::slack/post-query-string "views.publish"
                          {:user_id user
                           :view
                           (blocks/to-json
                             (screens/home))}])
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
        (tasks/publish! [::slack/views-open trigger_id screens/shortcut-modal]))

    "block_actions"                                         ; User acted on existing modal
    ;; Branch on specifics of given action
    (case action_id
      "admin:team-broadcast"
      (case view-type
        "home"  (tasks/publish! [::slack/views-open trigger_id screens/team-broadcast-modal-compose])
        "modal" (tasks/publish! [::slack/views-update view_id screens/team-broadcast-modal-compose]))

      "user:team-broadcast-response"
      (tasks/publish! [::slack/views-open trigger_id screens/team-broadcast-response])

      "user:team-broadcast-response-status"
      (tasks/publish! [::slack/views-update view_id screens/team-broadcast-response-status])

      "user:team-broadcast-response-achievement"
      (tasks/publish! [::slack/views-update view_id screens/team-broadcast-response-achievement])

      "user:team-broadcast-response-help"
      (tasks/publish! [::slack/views-update view_id screens/team-broadcast-response-help])
      
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
      (tasks/publish! [::request-updates message-text channel-ids]))
    (println [:unhandled-modal payload-type])))