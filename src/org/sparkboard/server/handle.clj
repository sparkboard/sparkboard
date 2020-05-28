(ns org.sparkboard.server.handle
  "HTTP request handlers"
  (:require [clojure.string :as string]
            [jsonista.core :as json]
            [org.sparkboard.server.slack.core :as slack]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.server.slack.screens :as screens]
            [ring.util.http-response :as http]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Utility fns

(defn read-json [v]
  (json/read-value v (json/object-mapper {:decode-key-fn keyword})))

(defn decode-text-input [s]
  ;; Slack appears to use some (?) of
  ;; `application/x-www-form-urlencoded` for at least multiline text
  ;; input, specifically replacing spaces with `+`
  (string/replace s "+" " "))

(defn request-updates [msg reply-channel]
  ;; TODO Write broadcast to Firebase
  (log/info "[request-updates] msg:" msg)
  (mapv #(slack/web-api "chat.postMessage"
                         {:channel %
                          :blocks (hiccup/->blocks-json (screens/team-broadcast-message msg reply-channel))})
        (keep (fn [{:strs [is_member id]}]
                ;; TODO ensure bot joins team-channels when they are created
                (when is_member id))
              (get (slack/web-api "channels.list") "channels"))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Individual/second-tier handlers

(defn block-actions
  "Handler for specific block actions.
  Branches on the bespoke action ID (set in server.slack.screens)."
  [payload]
  (log/info "[block-actions!] payload" payload)
  (let [view-id (get-in payload [:container :view_id])]
    (case (-> payload :actions first :action_id)
      "admin:team-broadcast"
      (case (get-in payload [:view :type])
        "home"  (slack/web-api "views.open" {:trigger_id (:trigger_id payload)
                                              :view (hiccup/->blocks-json (screens/team-broadcast-modal-compose))} )
        "modal" (slack/web-api "views.update" {:view_id view-id
                                                :view (hiccup/->blocks-json (screens/team-broadcast-modal-compose))}))

      "user:team-broadcast-response"
      (slack/web-api "views.open" {:trigger_id (:trigger_id payload)
                                    :view (hiccup/->blocks-json
                                           (screens/team-broadcast-response (->> payload
                                                                                 :message
                                                                                 :blocks last
                                                                                 :elements first
                                                                                 ;; text between brackets with lookahead/lookbehind:
                                                                                 :text (re-find #"(?<=\[).+?(?=\])"))))})

      "user:team-broadcast-response-status"
      (slack/web-api "views.update" {:view_id view-id
                                      :view (hiccup/->blocks-json
                                             (screens/team-broadcast-response-status
                                              (get-in payload [:view :private_metadata])))})
      "user:team-broadcast-response-achievement"
      (slack/web-api "views.update" {:view_id view-id
                                      :view (hiccup/->blocks-json
                                             (screens/team-broadcast-response-achievement
                                              (get-in payload [:view :private_metadata])))})
      "user:team-broadcast-response-help"
      (slack/web-api "views.update" {:view_id view-id
                                      :view (hiccup/->blocks-json
                                             (screens/team-broadcast-response-help
                                              (get-in payload [:view :private_metadata])))})

      "broadcast2:channel-select" ;; refresh same view then save selection in private metadata
      (slack/web-api "views.update" {:view_id view-id
                                      :view (hiccup/->blocks-json
                                             (screens/team-broadcast-modal-compose (-> payload :actions first :selected_conversation)))})

      ;; Default: failure XXX `throw`?
      (log/error [:unhandled-block-action (-> payload :actions first :action-id)]))))

(defn submission
  "Handler for 'Submit' press on any Slack modal."
  [payload]
  (log/info "[submission!] payload:" payload)
  (let [state (get-in payload [:view :state :values])]
    (cond
      ;; Admin broadcast: request for project update
      (-> state :sb-input1 :broadcast2:text-input)
      (request-updates (decode-text-input (get-in state [:sb-input1 :broadcast2:text-input :value]))
                       (get-in payload [:view :private_metadata]))

      ;; User broadcast response: describe current status
      (or (-> state :sb-project-status1 :user:status-input)
          (-> state :sb-project-achievement1 :user:achievement-input)
          (-> state :sb-project-help1 :user:help-input))
      (slack/web-api "chat.postMessage"
                      {:blocks (hiccup/->blocks-json
                                (screens/team-broadcast-response-msg
                                 "FIXME TODO project"
                                 (-> (or (get-in state [:sb-project-status1 :user:status-input])
                                         (get-in state [:sb-project-achievement1 :user:achievement-input])
                                         (get-in state [:sb-project-help1 :user:help-input]))
                                     :value
                                     decode-text-input)))
                       :channel (get-in payload [:view :private_metadata])}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Top level API

(defn event
  "Slack Event"
  [evt]
  (log/debug "[handle/event] evt:" evt)
  (case (get evt :type)
    "app_home_opened" (slack/web-api "views.publish"
                                      {:user_id (:user evt)
                                       :view (hiccup/->blocks-json (screens/home {} ;; FIXME props
                                                                                ))})    
    nil))

(defn interaction
  "Slack Interaction (e.g. global shortcut or modal)"
  [payload]
  (log/info "[handle/interaction] payload:" payload)
  (let [;; ts (-> payload :actions first :action_ts (some-> perf/slack-ts-ms))
        slack-team-id (get-in payload [:team :id])
        slack-user-id (get-in payload [:user :id])]
    (case (:type payload)
      ;; Slack "Global shortcut"
      ;; TODO `future`?
      "shortcut" (slack/web-api "views.open"
                                 {:trigger_id (:trigger_id payload)
                                  ;; FIXME `props` not empty map:
                                  :view (hiccup/->blocks-json (screens/shortcut-modal {}))})

      ;; ;; User acted on existing view
      "block_actions" (block-actions payload)

      ;; "Submit" button pressed; modal submitted
      "view_submission" (submission payload)

      ;; TODO throw?
      (log/error [:unhandled-event (:type payload)]))))

(defn incoming
  "All-purpose handler for Slack requests"
  [req]
  (log/info "[incoming] =====================================================================")
  (log/info "[incoming] request:" req)
  ;; TODO verify that requests come from Slack https://api.slack.com/authentication/verifying-requests-from-slack
  (cond (-> req :params :challenge) (http/ok (-> req :params :challenge))
        (-> req :params :event)     (do (future (event (-> req :params :event)))
                                        (http/ok))
        (-> req :params :payload)   (do (future (interaction (read-json (-> req :params :payload))))
                                        ;; Submissions require an empty body
                                        (http/ok))
        ;; Has not yet been required:
        :else (log/error [:unhandled-request req])))
