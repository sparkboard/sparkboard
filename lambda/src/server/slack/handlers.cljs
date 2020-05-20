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

(defn request-updates! [msg]
  ;; TODO
  ;; Write broadcast to Firebase
  (p/let [channels (p/-> (slack/get+ "channels.list")
                         (j/get :channels)
                         (->> (keep (j/fn [^:js {:keys [is_member id]}]
                                      ;; TODO
                                      ;; ensure bot joins team-channels when they are created
                                      (when is_member id)))))]
    (p/->> (map (partial send-slack-msg+ msg) channels)
           (p/all)
           (map http/assert-ok))))

(tasks/register-var! `request-updates!)

(defn handle-event! [{:as event
                      event-type :type
                      :keys [user channel tab]}]
  (case event-type
    "app_home_opened"
    {:task [`slack/post-query-string+ "views.publish"
            {:user_id user
             :view
             (blocks/to-json
               (screens/home))}]}
    nil))

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
  (case payload-type

    ; Slack "Global shortcut"
    "shortcut" {:task [`slack/views-open! trigger_id screens/shortcut-modal]}

    ; User acted on existing view
    "block_actions"
    (case action_id
      "admin:team-broadcast"
      (case view-type
        "modal" {:task [`slack/views-update! view_id screens/team-broadcast-modal-compose]}
        "home" {:task [`slack/views-open! trigger_id screens/team-broadcast-modal-compose]})

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
    {:task [`request-updates! (decode-text-input (get-in payload [:view
                                                                  :state
                                                                  :values
                                                                  :sb-input1
                                                                  :broadcast2:text-input
                                                                  :value]))]}
    (println [:unhandled-event payload-type])))