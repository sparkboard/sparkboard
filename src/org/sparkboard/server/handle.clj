(ns org.sparkboard.server.handle
  "Request handlers"
  (:require [org.sparkboard.server.slack.core :as slack]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.server.slack.screens :as screens]
            [ring.util.http-response :as http]))

(defn challenge
  "Slack API identification challenge"
  [req]
  (http/ok (-> req :body-params :challenge)))

(defn event
  "Slack Event"
  [{:as req :keys [body-params]}]
  (case (get-in body-params [:event :type])
    "app_home_opened" (slack/web-api "views.publish"
                                     {:user_id (:user event)
                                      :view (hiccup/->blocks-json (screens/home {} ;; FIXME props
                                                                                ))})    
    nil))

(defn interaction
  "Slack Interaction (e.g. global shortcut or modal)"
  [{:as req {:keys [payload]} :body-params}]
  (let [;; ts (-> payload :actions first :action_ts (some-> perf/slack-ts-ms))
        slack-team-id (get-in payload [:team :id])
        slack-user-id (get-in payload [:user :id])]
    (case (:type payload)
      ;; Slack "Global shortcut"
      ;; XXX cljs version sends token from request -- I think we can rely on token from config
      "shortcut" (slack/web-api "views.open"
                                {:trigger_id (:trigger_id payload)
                                 :view (screens/shortcut-modal {} ;; FIXME props
                                        )})
        
      ;; ;; User acted on existing view
      ;; TODO "block_actions" (handle-block-actions! token payload
      ;;                                        action_id trigger_id view_id
      ;;                                        view-type)

      ;; ;; "Submit" button pressed; modal submitted
      ;; TODO "view_submission" (handle-submission! token payload)

      ;; TODO throw? or log as error
      (println [:unhandled-event (:type payload)]))))
