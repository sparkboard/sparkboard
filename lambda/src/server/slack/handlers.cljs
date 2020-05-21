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
  ;; TODO
  ;; Write broadcast to Firebase
  (p/let [channels (p/-> (slack/get+ "channels.list" {:token token})
                         (j/get :channels)
                         (->> (keep (j/fn [^:js {:keys [is_member id]}]
                                      ;; TODO
                                      ;; ensure bot joins team-channels when they are created
                                      (when is_member id)))))]
    (p/->> (map (partial send-slack-blocks+ token (screens/team-broadcast-message msg)) channels)
           (p/all)
           (map http/assert-ok))))

(tasks/register-var! `request-updates!)

(defn handle-event! [token {:as event
                            event-type :type
                            :keys [user channel tab]}]
  (case event-type
    "app_home_opened"
    {:task [`slack/post+ "views.publish"
            {:token token
             :query {:user_id user
                     :view
                     (blocks/to-json
                       (screens/home))}}]}
    nil))

(defn decode-text-input [s]
  ;; Slack appears to use some (?) of
  ;; `application/x-www-form-urlencoded` for at least multiline text
  ;; input, specifically replacing spaces with `+`
  (str/replace s "+" " "))

(defn handle-interaction! [token {payload-type :type
                                  :keys [trigger_id]
                                  [{:keys [action_id]}] :actions
                                  {view-type :type} :view
                                  {:as container :keys [view_id]} :container
                                  :as payload}]
  (case payload-type

    ; Slack "Global shortcut"
    "shortcut" {:task [`slack/views-open! token trigger_id screens/shortcut-modal]}

    ; User acted on existing view
    "block_actions"
    (case action_id
      "admin:team-broadcast"
      (case view-type
        "home"  {:task [`slack/views-open!   token trigger_id screens/team-broadcast-modal-compose]})
        "modal" {:task [`slack/views-update! token view_id    screens/team-broadcast-modal-compose]}

      "user:team-broadcast-response"
      (tasks/publish! {:task [`slack/views-open! token trigger_id screens/team-broadcast-response]})

      "user:team-broadcast-response-status"
      (tasks/publish! {:task [`slack/views-update! token view_id screens/team-broadcast-response-status]})

      "user:team-broadcast-response-achievement"
      (tasks/publish! {:task [`slack/views-update! token view_id screens/team-broadcast-response-achievement]})

      "user:team-broadcast-response-help"
       (tasks/publish! {:task [`slack/views-update! token view_id screens/team-broadcast-response-help]})
      
      ;; TODO FIXME
      #_"broadcast2:channel-select"
      #_(slack/views-push! (j/get-in payload ["container" "view_id"])
                           [:modal {:title "Compose Broadcast"
                                    :blocks blocks-broadcast-2
                                    :submit [:plain_text "Submit"]}])
      (println [:unhandled-block-action action_id]))

    ; "Submit" button pressed
    "view_submission"
    ;; In the future we will need to branch on other data
    {:task [`request-updates! token (decode-text-input (get-in payload [:view
                                                                        :state
                                                                        :values
                                                                        :sb-input1
                                                                        :broadcast2:text-input
                                                                        :value]))]}
    (println [:unhandled-event payload-type])))
