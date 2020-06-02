(ns org.sparkboard.server.server
  "HTTP server handling Slack requests"
  (:gen-class)
  (:require [bidi.ring :as bidi.ring]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.firebase.tokens :as fire-tokens]
            [org.sparkboard.js-convert :refer [json->clj]]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.server.slack.core :as slack]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.server.slack.screens :as screens]
            [org.sparkboard.slack.oauth :as slack-oauth]
            [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.slack.urls :as urls]
            [org.sparkboard.util :as u]
            [org.sparkboard.util.future :refer [try-future]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.util.http-response :as http]
            [ring.util.request]
            [taoensso.timbre :as log]
            [timbre-ns-pattern-level :as timbre-patterns])
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (org.apache.commons.codec.binary Hex)))

(def log-levels
  (case (env/config :env "dev")
    "dev" '{:all :info
            org.sparkboard.firebase.jvm :trace
            org.sparkboard.server.slack.core :trace}
    "staging" {:all :warn}
    "prod" {:all :warn}))

(-> {:middleware [(timbre-patterns/middleware
                    (reduce-kv
                      (fn [m k v] (assoc m (cond-> k (symbol? k) (str)) v))
                      {} log-levels))]
     :level :trace}
    ;; add :dev.logging/tap? to .local.config.edn to use tap>
    (cond-> (env/config :dev.logging/tap?)
            (assoc :appenders
                   {:tap> {:min-level nil
                           :enabled? true
                           :output-fn (fn [{:keys [?ns-str ?line vargs]}]
                                        (into [(-> ?ns-str
                                                   (str/replace "org.sparkboard." "")
                                                   (str ":" ?line)
                                                   (symbol))] vargs))
                           :fn (comp tap> force :output_)}}))
    (log/merge-config!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Utility fns

(defn decode-text-input [s]
  ;; Slack appears to use some (?) of
  ;; `application/x-www-form-urlencoded` for at least multiline text
  ;; input, specifically replacing spaces with `+`
  (str/replace s "+" " "))

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

(defn send-welcome-message! [context {:as user :keys [id]}]
  (let [url (urls/link-sparkboard-account context)]
    ;; TODO
    ;; send welcome message to user, with a button (using ^url) to link their new Slack account with Sparkboard.
    )
  )

(defn update-user-home-tab! [context]
  (slack/web-api "views.publish" {:auth/token (:slack/token context)}
                 {:user_id (:slack/user-id context)
                  :view (hiccup/->blocks-json (screens/home context))}))

(defn create-linked-channel! [context {:sparkboard.project/keys [title url]
                                       :keys [sparkboard/project-id
                                              sparkboard/account-id]}]
  (comment
    (let [channel ...create-channel-via-slack-api!]
      ;; TODO
      ;;   - name should be `team-${SOMETHING}`, derived from title?
      ;;   - include title + url in channel description

      ;; after creating the channel, link it to the project
      (slack-db/link-channel-to-project! (assoc context
                                           :slack/channel-id (:id channel)
                                           :sparkboard/project-id project-id))))
  )

(defn add-user-to-channel! [context {:keys [slack/user-id
                                            slack/channel-id]}]
  ;; TODO
  ;; use slack api to add user-id to channel-id
  )

(defn slack-context
  "Returns context-map expected by functions handling Slack events"
  [team-id user-id]
  {:pre [team-id user-id]}
  (let [app-id (-> env/config :slack :app-id)
        team (slack-db/linked-team team-id)
        user (slack-db/linked-user user-id)]
    {:slack/app-id app-id
     :slack/team-id team-id
     :slack/user-id user-id
     :slack/token (get-in team [:app (keyword app-id) :bot-token])
     :sparkboard/account-id (:account-id user)
     :sparkboard/board-id (:board-id team)
     :sparkboard/jvm-root (-> env/config :sparkboard/jvm-root)
     :env (:env env/config "dev")}))

(defn req-token [req]
  (or (some-> (:headers req) (get "authorization") (str/replace #"^Bearer: " ""))
      (-> req :params :token)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Top level API

(defn slack-event
  "Slack Event"
  [params]
  (try-future
    (let [evt (:event params)
          context (slack-context (:team_id params) (:user evt))]
      (log/debug "[event] evt:" evt)
      (log/debug "[event] context:" context)
      (case (get evt :type)
        "app_home_opened" (update-user-home-tab! context)
        "team_join" (send-welcome-message! context (:user evt))
        nil)))
  (http/ok))

(defn slack-interaction
  "Slack Interaction (e.g. global shortcut or modal) handler"
  [params]
  (try-future
    (let [payload (json->clj (:payload params))
          context (slack-context (-> payload :team :id)
                                 (-> payload :user :id))]
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
        (log/error [:unhandled-event (:type payload)]))))
  (http/ok))

(defn wrap-sparkboard-verify
  "must be a Sparkboard server request"
  [claims-check f]
  (fn [req]
    (if-let [auth (try (some-> (req-token req)
                               (fire-tokens/decode)
                               (u/guard claims-check))
                       (catch Exception e nil))]
      (f (assoc req :auth/token-claims auth))
      {:status 401
       :headers {"Content-Type" "text/plain"}
       :body "Invalid token"})))

(defn nav-project-channel [{:as req
                            {:sparkboard/keys [account-id board-id]} :auth/token-claims
                            {:keys [project-id]} :params}]
  (let [{:keys [channel-id]} (or (slack-db/project->linked-channel project-id)
                                 ;; TODO make channel!
                                 )]

    (http/found
      (urls/app-redirect {:app (-> env/config :slack :app-id)
                          :team (slack-db/board->team board-id)
                          :channel channel-id}))))

(defn hash-hmac256 [secret message]
  (-> (Mac/getInstance "HmacSHA256")
      (doto (.init (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")))
      (.doFinal (.getBytes message "UTF-8"))
      (Hex/encodeHexString)))

(defn wrap-saved-body
  "Duplicates body of urlencoded requests for later parsing.
   Must go before/outside ring middleware (i.e. after in the `->`
  thread), so it still has access to untouched `body` of request."
  [f]
  (fn [req]
    (log/warn "[wrap-saved-body" (ring.util.request/urlencoded-form? req))
    (if (ring.util.request/urlencoded-form? req)
      (f (let [input (slurp (:body req)
                            :encoding (or (ring.util.request/character-encoding req)
                                          "UTF-8"))]
           (assoc req
                  :body  (io/input-stream (.getBytes input))
                  :body-string input)))
      (f req))))

(defn wrap-slack-verify
  "must be a Slack api request"
  [f]
  ;; https://api.slack.com/authentication/verifying-requests-from-slack
  (fn [req]
    (let [body-string (:body-string req) ; we must rely on saved body, because `ring` destructively consumes the inputstream of urlencoded requests. therefore this depends on work of `wrap-saved-body` and must come after it in the middleware stack (ergo, _before_ in the thread)
          {:strs [x-slack-request-timestamp
                  x-slack-signature]} (:headers req)
          secret (-> env/config :slack :signing-secret)
          message (str "v0:" x-slack-request-timestamp ":" body-string)
          hashed (str "v0=" (hash-hmac256 secret message))]
      (if (= hashed x-slack-signature)
        (f req)
        (http/unauthorized)))))

(defn sparkboard-action
  "Event triggered by Sparkboard (legacy)"
  [{:as req :keys [params]}]
  (let [{:keys [action slack/team-id slack/user-id]} params
        context (slack-context team-id user-id)]
    (case action
      :update-home! (update-user-home-tab! context)
      :create-linked-channel! (create-linked-channel! context params))))

(def routes
  ["/" {"slack-api" (wrap-slack-verify
                      (fn [{:as req :keys [params]}]
                        (log/trace "[slack-api]" req)
                        (cond (:challenge params) (http/ok (:challenge params))
                              (:event params) (slack-event params)
                              (:payload params) (slack-interaction params)
                              :else (log/error [:unhandled-request req]))))
        "slack-api/oauth-redirect" slack-oauth/redirect
        "slack/sparkboard-action" (wrap-sparkboard-verify :sparkboard/server-request?
                                                          sparkboard-action)
        ["slack/project-channel/" :project-id] (wrap-sparkboard-verify (every-pred :sparkboard/account-id
                                                                                   :sparkboard/board-id)
                                                                       nav-project-channel)
        "slack/install" slack-oauth/install-redirect}])

(def app
  (-> (bidi.ring/make-handler routes)
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)
      (ring.middleware.format/wrap-restful-format {:formats [:json-kw :transit-json]})
      ;; Must go before/outside/"after" ring middleware, so it still
      ;; has access to untouched `body` of request:
      wrap-saved-body))

(defonce server (atom nil))

(defn stop-server! []
  (when-not (nil? @server)
    (.stop @server)))

(defn restart-server!
  "Setup fn.
  Starts HTTP server, stopping existing HTTP server first if necessary."
  [port]
  (stop-server!)
  (reset! server (run-jetty #'app {:port port :join? false})))

(defn -main []
  (log/info "Starting server" {:jvm (System/getProperty "java.vm.version")})
  (fire-jvm/sync-all)                                       ;; cache firebase db locally
  (restart-server! (or (some-> (System/getenv "PORT") (Integer/parseInt)) 3000)))

(comment
  (-main)

  @server

  ;; See also `dev.restlient` at project root
  )
