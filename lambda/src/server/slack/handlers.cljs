(ns server.slack.handlers
  (:require [applied-science.js-interop :as j]
            [cljs.pprint :as pp]
            [clojure.string :as str]
            [kitchen-async.promise :as p]
            [org.sparkboard.http :as http]
            [server.slack.hiccup :as hiccup]
            [server.deferred-tasks :as tasks]
            [server.slack :as slack]
            [server.slack.screens :as screens]))

(defn send-slack-blocks+ [token blocks channel]
  (p/->> (slack/post+ "chat.postMessage"
                      {:query {:channel channel             ;; id or name
                               :blocks (hiccup/->blocks-json blocks)}
                       :token token})
         (prn "slack blocks response:")))

(defn send-slack-msg+ [token msg channel]
  (slack/post+ "chat.postMessage"
               {:query {:channel channel                    ;; id or name
                        :text msg}
                :token token}))

(defn request-updates! [token msg reply-channel]
  ;; TODO Write broadcast to Firebase
  (p/let [channels (p/-> (slack/get+ "channels.list" {:token token})
                         (j/get :channels)
                         (->> (keep (j/fn [^:js {:keys [is_member id]}]
                                      ;; TODO ensure bot joins team-channels when they are created
                                      (when is_member id)))))]
    (p/->> (map (partial send-slack-blocks+ token (screens/team-broadcast-message msg reply-channel)) channels)
           (p/all)
           (map http/assert-ok))))

(tasks/register-handler! `request-updates!)

(defn report-project-status! [token msg reply-channel]
  (prn "[report-project-status!] msg:" msg)
  ;; TODO Write project status to Firebase
  (http/assert-ok (send-slack-blocks+ token
                                      (screens/team-broadcast-response-msg "FIXME TODO project" msg)
                                      reply-channel)))

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
                     (hiccup/->blocks-json
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
  [token payload action-id trigger-id view-id view-type]
  #_ (prn "[handle-block-actions!] payload" payload)
  (case action-id
    "admin:team-broadcast"
    (case view-type
      "home"  {:task [`slack/views-open!   token trigger-id (screens/team-broadcast-modal-compose)]}
      "modal" {:task [`slack/views-update! token view-id    (screens/team-broadcast-modal-compose)]})

    "user:team-broadcast-response"
    {:task [`slack/views-open! token trigger-id (screens/team-broadcast-response (->> payload
                                                                                      :message
                                                                                      :blocks last
                                                                                      :elements first
                                                                                      ;; text between brackets with lookahead/lookbehind:
                                                                                      :text (re-find #"(?<=\[).+?(?=\])")))]}
    
    "user:team-broadcast-response-status"
    {:task [`slack/views-update! token view-id (screens/team-broadcast-response-status
                                                (get-in payload [:view :private_metadata]))]}
    "user:team-broadcast-response-achievement"
    {:task [`slack/views-update! token view-id (screens/team-broadcast-response-achievement
                                                (get-in payload [:view :private_metadata]))]}
    "user:team-broadcast-response-help"
    {:task [`slack/views-update! token view-id (screens/team-broadcast-response-help
                                                (get-in payload [:view :private_metadata]))]}
    
    "broadcast2:channel-select" ;; refresh same view then save selection in private metadata
    {:task [`slack/views-update! token view-id (screens/team-broadcast-modal-compose (-> payload :actions first :selected_conversation))]}

    ;; Default: failure
    (prn [:unhandled-block-action action-id])))

(defn handle-submission! [token payload]
  #_ (prn "[handle-submission!] payload:" payload)
  #_ (prn "[handle-submission!] private metadata:" (get-in payload [:view :private_metadata]))  
  (let [state (get-in payload [:view :state :values])]
    (cond
      ;; Admin broadcast: request for project update
      (-> state :sb-input1 :broadcast2:text-input)
      {:task [`request-updates! token (decode-text-input (get-in state [:sb-input1 :broadcast2:text-input :value])) (get-in payload [:view :private_metadata])]}

      ;; User broadcast response: describe current status
      (or (-> state :sb-project-status1 :user:status-input)
          (-> state :sb-project-achievement1 :user:achievement-input)
          (-> state :sb-project-help1 :user:help-input))
      {:task [`report-project-status! token
              (-> (or (get-in state [:sb-project-status1 :user:status-input])
                      (get-in state [:sb-project-achievement1 :user:achievement-input])
                      (get-in state [:sb-project-help1 :user:help-input]))
                  :value
                  decode-text-input)
              (get-in payload [:view :private_metadata])]})))

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
    "block_actions" (handle-block-actions! token payload
                                           action_id trigger_id view_id
                                           view-type)

    ; "Submit" button pressed
    "view_submission" (handle-submission! token payload)
    
    (println [:unhandled-event payload-type])))
