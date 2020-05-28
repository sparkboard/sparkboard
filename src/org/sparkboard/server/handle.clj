(ns org.sparkboard.server.handle
  "HTTP request handlers"
  (:require [clojure.string :as string]
            [org.sparkboard.server.slack.core :as slack]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.server.slack.screens :as screens]
            [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.js-convert :refer [json->clj]]
            [server.common :as common]
            [ring.util.http-response :as http]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Utility fns

(defn decode-text-input [s]
  ;; Slack appears to use some (?) of
  ;; `application/x-www-form-urlencoded` for at least multiline text
  ;; input, specifically replacing spaces with `+`
  (string/replace s "+" " "))

(defn request-updates [{:keys [slack/token]} msg reply-channel]
  ;; TODO Write broadcast to Firebase
  (log/info "[request-updates] msg:" msg)
  (let [blocks (hiccup/->blocks-json (screens/team-broadcast-message msg reply-channel))]
    (mapv #(slack/web-api "chat.postMessage"
                          {:channel %
                           :blocks blocks
                           :slack/token token})
          (keep (fn [{:strs [is_member id]}]
                  ;; TODO ensure bot joins team-channels when they are created
                  (when is_member id))
                (get (slack/web-api "channels.list" {:slack/token token}) "channels")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Individual/second-tier handlers

(defn block-actions
  "Handler for specific block actions.
  Branches on the bespoke action ID (set in server.slack.screens)."
  [{:keys [slack/token]} payload]
  (log/info "[block-actions!] payload" payload)
  (let [view-id (get-in payload [:container :view_id])]
    (case (-> payload :actions first :action_id)
      "admin:team-broadcast"
      (case (get-in payload [:view :type])
        "home" (slack/web-api "views.open" {:trigger_id (:trigger_id payload)
                                            :view (hiccup/->blocks-json (screens/team-broadcast-modal-compose))
                                            :slack/token token})
        "modal" (slack/web-api "views.update" {:view_id view-id
                                               :view (hiccup/->blocks-json (screens/team-broadcast-modal-compose))
                                               :slack/token token}))

      "user:team-broadcast-response"
      (slack/web-api "views.open" {:trigger_id (:trigger_id payload)
                                   :view (hiccup/->blocks-json
                                           (screens/team-broadcast-response (->> payload
                                                                                 :message
                                                                                 :blocks last
                                                                                 :elements first
                                                                                 ;; text between brackets with lookahead/lookbehind:
                                                                                 :text (re-find #"(?<=\[).+?(?=\])"))))
                                   :slack/token token})

      "user:team-broadcast-response-status"
      (slack/web-api "views.update" {:view_id view-id
                                     :view (hiccup/->blocks-json
                                             (screens/team-broadcast-response-status
                                               (get-in payload [:view :private_metadata])))
                                     :slack/token token})
      "user:team-broadcast-response-achievement"
      (slack/web-api "views.update" {:view_id view-id
                                     :view (hiccup/->blocks-json
                                             (screens/team-broadcast-response-achievement
                                               (get-in payload [:view :private_metadata])))
                                     :slack/token token})
      "user:team-broadcast-response-help"
      (slack/web-api "views.update" {:view_id view-id
                                     :view (hiccup/->blocks-json
                                             (screens/team-broadcast-response-help
                                               (get-in payload [:view :private_metadata])))
                                     :slack/token token})

      "broadcast2:channel-select"                           ;; refresh same view then save selection in private metadata
      (slack/web-api "views.update" {:view_id view-id
                                     :view (hiccup/->blocks-json
                                             (screens/team-broadcast-modal-compose (-> payload :actions first :selected_conversation)))
                                     :slack/token token})

      ;; Default: failure XXX `throw`?
      (log/error [:unhandled-block-action (-> payload :actions first :action-id)]))))

(defn submission
  "Handler for 'Submit' press on any Slack modal."
  [{:keys [slack/token]} payload]
  (log/info "[submission!] payload:" payload)
  (let [state (get-in payload [:view :state :values])]
    (cond
      ;; Admin broadcast: request for project update
      (-> state :sb-input1 :broadcast2:text-input)
      (request-updates props
                       (decode-text-input (get-in state [:sb-input1 :broadcast2:text-input :value]))
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
                      :channel (get-in payload [:view :private_metadata])
                      :slack/token token}))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Top level API

(defn event
  "Slack Event"
  [props evt]
  (tap> ["[handle/event] evt:" evt])
  (case (get evt :type)
    "app_home_opened" (slack/web-api "views.publish"
                                     {:user_id (:slack/user-id props)
                                      :view (hiccup/->blocks-json (screens/home {} ;; FIXME props
                                                                                ))
                                      :slack/token (:slack/token props)})
    nil))

(defn interaction
  "Slack Interaction (e.g. global shortcut or modal)"
  [props payload]
  (tap> ["[handle/interaction]" (:type payload) payload])
  (case (:type payload)
    ;; Slack "Global shortcut"
    ;; TODO `future`?
    "shortcut" (slack/web-api "views.open"
                              {:trigger_id (:trigger_id payload)
                               ;; FIXME `props` not empty map:
                               :view (hiccup/->blocks-json (screens/shortcut-modal {}))})

    ;; ;; User acted on existing view
    "block_actions" (block-actions props payload)

    ;; "Submit" button pressed; modal submitted
    "view_submission" (submission props payload)

    ;; TODO throw?
    (log/error [:unhandled-event (:type payload)])))

(defn incoming
  "All-purpose handler for Slack requests"
  [{:as req :keys [params]}]
  (log/info "[incoming] =====================================================================")
  ;(log/info "[incoming] request:" req)
  ;; TODO verify that requests come from Slack https://api.slack.com/authentication/verifying-requests-from-slack
  (let [[kind data team-id user-id] (cond (:challenge params)
                                          [:challenge (:challenge params)]

                                          (:event params)
                                          (let [event (:event params)]
                                            [:event event (:team_id params) (:user event)])

                                          (:payload params)
                                          (let [payload (json->clj (:payload params))]
                                            [:interaction
                                             payload
                                             (get-in payload [:team :id])
                                             (get-in payload [:user :id])])
                                          ;; Has not yet been required:
                                          :else (log/error [:unhandled-request req]))
        app-id (-> common/config :slack :app-id)
        team (slack-db/linked-team team-id)
        props #:slack{:app-id app-id
                      :team-id team-id
                      :user-id user-id
                      :token (get-in team [:app (keyword app-id) :bot-token])}]
    (case kind :challenge (http/ok data)
               :event (do (future (event props data))
                          (http/ok))
               :interaction (do (future (interaction props data))
                                ;; Submissions require an empty body
                                (http/ok))
               :else (log/error [:unhandled-request req]))))
