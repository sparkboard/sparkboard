(ns org.sparkboard.server.server
  "HTTP server handling Slack requests"
  (:require [bidi.ring :as bidi.ring]
            [clojure.string :as string]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.js-convert :refer [json->clj]]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.server.slack.core :as slack]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.server.slack.screens :as screens]
            [org.sparkboard.slack.oauth :as slack-oauth]
            [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.slack.urls :as urls]
            [org.sparkboard.util.future :refer [try-future]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.util.http-response :as http]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Utility fns

(defn decode-text-input [s]
  ;; Slack appears to use some (?) of
  ;; `application/x-www-form-urlencoded` for at least multiline text
  ;; input, specifically replacing spaces with `+`
  (string/replace s "+" " "))

(defn request-updates [context msg reply-channel]
  ;; TODO Write broadcast to Firebase
  (log/debug "[request-updates] msg:" msg)
  (let [blocks (hiccup/->blocks-json (screens/team-broadcast-message msg reply-channel))]
    (mapv #(slack/web-api "chat.postMessage" {:auth/token (:slack/token context)}
                          {:channel % :blocks blocks})
          (keep (fn [{:strs [is_member id]}]
                  ;; TODO ensure bot joins team-channels when they are created
                  (when is_member id))
                (get (slack/web-api "channels.list" {:auth/token (:slack/token context)})
                     "channels")))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Individual/second-tier handlers

(defn block-actions
  "Handler for specific block actions.
  Branches on the bespoke action ID (set in server.slack.screens)."
  [context payload]
  (log/debug "[block-actions!] payload" payload)
  (let [view-id (get-in payload [:container :view_id])]
    (case (-> payload :actions first :action_id)
      "admin:team-broadcast"
      (case (get-in payload [:view :type])
        "home" (slack/web-api "views.open" {:auth/token (:slack/token context)}
                              {:trigger_id (:trigger_id payload)
                               :view (hiccup/->blocks-json (screens/team-broadcast-modal-compose))})
        "modal" (slack/web-api "views.update" {:auth/token (:slack/token context)}
                               {:view_id view-id
                                :view (hiccup/->blocks-json (screens/team-broadcast-modal-compose))}))

      "user:team-broadcast-response"
      (slack/web-api "views.open" {:auth/token (:slack/token context)}
                     {:trigger_id (:trigger_id payload)
                      :view (hiccup/->blocks-json
                              (screens/team-broadcast-response (->> payload
                                                                    :message
                                                                    :blocks last
                                                                    :elements first
                                                                    ;; text between brackets with lookahead/lookbehind:
                                                                    :text (re-find #"(?<=\[).+?(?=\])"))))})

      "user:team-broadcast-response-status"
      (slack/web-api "views.update" {:auth/token (:slack/token context)}
                     {:view_id view-id
                      :view (hiccup/->blocks-json
                              (screens/team-broadcast-response-status
                                (get-in payload [:view :private_metadata])))})
      "user:team-broadcast-response-achievement"
      (slack/web-api "views.update"
                     {:auth/token (:slack/token context)} {:view_id view-id
                                                           :view (hiccup/->blocks-json
                                                                   (screens/team-broadcast-response-achievement
                                                                     (get-in payload [:view :private_metadata])))})
      "user:team-broadcast-response-help"
      (slack/web-api "views.update" {:auth/token (:slack/token context)}
                     {:view_id view-id
                      :view (hiccup/->blocks-json
                              (screens/team-broadcast-response-help
                                (get-in payload [:view :private_metadata])))})

      "broadcast2:channel-select"                           ;; refresh same view then save selection in private metadata
      (slack/web-api "views.update" {:auth/token (:slack/token context)}
                     {:view_id view-id
                      :view (hiccup/->blocks-json
                              (screens/team-broadcast-modal-compose (-> payload :actions first :selected_conversation)))})

      ;; Default: failure XXX `throw`?
      (log/error [:unhandled-block-action (-> payload :actions first :action-id)]))))

(defn submission
  "Handler for 'Submit' press on any Slack modal."
  [context payload]
  (log/debug "[submission!] payload:" payload)
  (let [state (get-in payload [:view :state :values])]
    (cond
      ;; Admin broadcast: request for project update
      (-> state :sb-input1 :broadcast2:text-input)
      (request-updates context
                       (decode-text-input (get-in state [:sb-input1 :broadcast2:text-input :value]))
                       (get-in payload [:view :private_metadata]))

      ;; User broadcast response: describe current status
      (or (-> state :sb-project-status1 :user:status-input)
          (-> state :sb-project-achievement1 :user:achievement-input)
          (-> state :sb-project-help1 :user:help-input))
      (slack/web-api "chat.postMessage" {:auth/token (:slack/token context)}
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
  [context evt]
  (tap> ["[event] evt:" evt])
  (log/debug "[event] context:" context)
  (case (get evt :type)
    "app_home_opened" (slack/web-api "views.publish" {:auth/token (:slack/token context)}
                                     {:user_id (:slack/user-id context)
                                      :view (hiccup/->blocks-json (screens/home context))})
    nil))

(defn interaction
  "Slack Interaction (e.g. global shortcut or modal) handler"
  [context payload]
  (case (:type payload)
    ;; Slack "Global shortcut"
    "shortcut" (slack/web-api "views.open" {:auth/token (:slack/token context)}
                              {:trigger_id (:trigger_id payload)
                               :view (hiccup/->blocks-json (screens/shortcut-modal context))})

    ;; ;; User acted on existing view
    "block_actions" (block-actions context payload)

    ;; "Submit" button pressed; modal submitted
    "view_submission" (submission context payload)

    ;; TODO throw?
    (log/error [:unhandled-event (:type payload)])))

(defn incoming
  "All-purpose handler for Slack requests"
  [{:as req :keys [params]}]
  (tap> {:incoming req})
  (log/debug "[incoming] =====================================================================")
  ;; (log/debug "[incoming] request:" req)
  ;; TODO verify that requests come from Slack https://api.slack.com/authentication/verifying-requests-from-slack
  (if (:challenge params)
    (http/ok (:challenge params))
    (let [[kind data team-id user-id] (cond (:event params)
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
          app-id (-> env/config :slack :app-id)
          team (slack-db/linked-team team-id)
          user (slack-db/linked-user user-id)
          context {:slack/app-id app-id
                   :slack/team-id team-id
                   :slack/user-id user-id
                   :slack/token (get-in team [:app (keyword app-id) :bot-token])
                   :sparkboard/account-id (:account-id user)
                   :sparkboard/board-id (:board-id team)
                   :sparkboard.jvm/root (-> env/config :sparkboard.jvm/root)
                   :env (:env env/config "dev")}]
      (case kind
        :challenge (http/ok data)
        :event (do (try-future (event context data))
                   (http/ok))
        :interaction (do (try-future (interaction context data))
                         ;; Submissions require an empty body
                         (http/ok))
        :else (log/error [:unhandled-request req])))))

(defonce server (atom nil))

(defn stop-server! []
  (when-not (nil? @server)
    (.stop @server)))

(def routes
  ["/" {"slack-api" (fn [x] (#'incoming x))
        "slack-api/oauth-redirect" #'slack-oauth/redirect
        "slack/install" #'slack-oauth/install-redirect}])

(def app
  (-> (bidi.ring/make-handler routes)
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)
      (ring.middleware.format/wrap-restful-format {:formats [:json-kw]})))

(defn restart-server!
  "Setup fn.
  Starts HTTP server, stopping existing HTTP server first if necessary."
  [port]
  (stop-server!)
  (reset! server (run-jetty #'app {:port port :join? false})))

;; TODO, explicit init fn
(defonce _ (fire-jvm/sync-all))

(comment
  (restart-server! 3000)

  @server

  ;; See also `dev.restlient` at project root
  )
