(ns server.slack.handlers
  (:require [applied-science.js-interop :as j]
            [cljs.pprint :as pp]
            [clojure.string :as str]
            [kitchen-async.promise :as p]
            [server.blocks :as blocks]
            [server.deferred-tasks :as tasks]
            [org.sparkboard.http :as http]
            [server.slack :as slack]
            [server.slack.screens :as screens]))

(defn send-slack-blocks+ [token blocks channel]
  (p/->> (slack/post+ "chat.postMessage"
                      {:query {:channel channel             ;; id or name
                               :blocks (blocks/to-json blocks)}
                       :token token})
         ;; TODO better callback
         (println "slack blocks response:")))

(defn send-slack-msg+ [token msg channel]
  (slack/post+ "chat.postMessage"
               {:query {:channel channel                    ;; id or name
                        :text msg}
                :token token}))

(defn request-updates! [token msg]
  ;; TODO Write broadcast to Firebase
  (p/let [channels (p/-> (slack/get+ "channels.list" {:token token})
                         (j/get :channels)
                         (->> (keep (j/fn [^:js {:keys [is_member id]}]
                                      ;; TODO
                                      ;; ensure bot joins team-channels when they are created
                                      (when is_member id)))))]
    (p/->> (map (partial send-slack-blocks+ token (screens/team-broadcast-message msg)) channels)
           (p/all)
           (map http/assert-ok))))

(tasks/register-handler! `request-updates!)

(defn report-project-status! [token msg]
  (prn "[report-project-status!] msg:" msg)
  ;; TODO Write project status to Firebase
  (p/->> (send-slack-blocks+ token
                             (screens/team-broadcast-response-msg "FIXME project" msg)
                             "team-updates")
         (http/assert-ok)
         (println "report-project-status! response:")))

(tasks/register-handler! `report-project-status!)

(defn handle-event! [{:as props
                      :keys [slack/token]} {:as event
                                            event-type :type
                                            :keys [user channel tab]}]
  (case event-type
    "app_home_opened"
    {:task [`slack/post+ "views.publish"
            {:token token
             :query {:user_id user
                     :view
                     (blocks/to-json
                       (screens/home props))}}]}
    nil))

(defn decode-text-input [s]
  ;; Slack appears to use some (?) of
  ;; `application/x-www-form-urlencoded` for at least multiline text
  ;; input, specifically replacing spaces with `+`
  (str/replace s "+" " "))

(defn handle-block-actions!
  "Handler for specific block actions.
  Branches on the bespoke action ID (set in server.slack.screens)."
  [token action-id trigger-id view-id view-type]
  (case action-id
    "admin:team-broadcast"
    (case view-type
      "home"  {:task [`slack/views-open!   token trigger-id screens/team-broadcast-modal-compose]}
      "modal" {:task [`slack/views-update! token view-id    screens/team-broadcast-modal-compose]})

    "user:team-broadcast-response"
    {:task [`slack/views-open! token trigger-id screens/team-broadcast-response]}
    
    "user:team-broadcast-response-status"
    {:task [`slack/views-update! token view-id screens/team-broadcast-response-status]}

    "user:team-broadcast-response-achievement"
    {:task [`slack/views-update! token view-id screens/team-broadcast-response-achievement]}

    "user:team-broadcast-response-help"
    {:task [`slack/views-update! token view-id screens/team-broadcast-response-help]}
    
    ;; TODO FIXME
    #_"broadcast2:channel-select"
    #_(slack/views-push! (j/get-in payload ["container" "view-id"])
                         [:modal {:title "Compose Broadcast"
                                  :blocks blocks-broadcast-2
                                  :submit [:plain-text "Submit"]}])
    (println [:unhandled-block-action action-id])))

(defn handle-submission! [token payload]
  (prn "view_submission: payload:" payload)
  (let [state (get-in payload [:view :state :values])]
    (cond
      ;; Admin broadcast: request for project update
      (-> state :sb-input1 :broadcast2:text-input)
      {:task [`request-updates! token (decode-text-input (get-in payload [:view
                                                                          :state
                                                                          :values
                                                                          :sb-input1
                                                                          :broadcast2:text-input
                                                                          :value]))]}

      ;; User broadcast response: describe current status
      (or (-> state :sb-project-status1 :user:status-input)
          (-> state :sb-project-achievement1 :user:achievement-input)
          (-> state :sb-project-help1 :user:help-input))
      {:task [`report-project-status! token (-> (or (get-in state [:sb-project-status1 :user:status-input])
                                                    (get-in state [:sb-project-achievement1 :user:achievement-input])
                                                    (get-in state [:sb-project-help1 :user:help-input]))
                                                :value
                                                decode-text-input)]})))

(defn handle-interaction! [{:as props :keys [slack/token]}
                           {payload-type :type
                            :keys [trigger_id]
                            [{:keys [action_id]}] :actions
                            {view-type :type} :view
                            {:as container :keys [view_id]} :container
                            :as payload}]
  (case payload-type
    ; Slack "Global shortcut"
    "shortcut" {:task [`slack/views-open! token trigger_id (screens/shortcut-modal props)]}

    ; User acted on existing view
    "block_actions" (handle-block-actions! token
                                           action_id
                                           trigger_id
                                           view_id
                                           view-type)

    ; "Submit" button pressed
    "view_submission" (handle-submission! token payload)
    
    (println [:unhandled-event payload-type])))
