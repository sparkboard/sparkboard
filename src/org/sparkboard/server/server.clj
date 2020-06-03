(ns org.sparkboard.server.server
  "HTTP server handling Slack requests"
  (:gen-class)
  (:require [bidi.ring :as bidi.ring]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.firebase.tokens :as fire-tokens]
            [org.sparkboard.http :as http]
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
            [ring.util.http-response :as ring.http]
            [ring.util.request]
            [taoensso.timbre :as log]
            [timbre-ns-pattern-level :as timbre-patterns])
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (org.apache.commons.codec.binary Hex)))

(def log-levels
  (case (env/config :env "dev")
    "dev" '{:all :info
            org.sparkboard.server.server :trace
            ;org.sparkboard.firebase.jvm :trace
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
    (mapv #(slack/web-api "chat.postMessage" {:auth/token (:slack/bot-token context)}
                          {:channel % :blocks blocks})
          (keep (fn [{:keys [is_member id]}]
                  ;; TODO ensure bot joins team-channels when they are created
                  (when is_member id))
                (:channels (slack/web-api "channels.list" {:auth/token (:slack/bot-token context)}))))))


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
        "home" (slack/web-api "views.open" {:auth/token (:slack/bot-token context)}
                              {:trigger_id (:trigger_id payload)
                               :view (hiccup/->blocks-json (screens/team-broadcast-modal-compose))})
        "modal" (slack/web-api "views.update" {:auth/token (:slack/bot-token context)}
                               {:view_id view-id
                                :view (hiccup/->blocks-json (screens/team-broadcast-modal-compose))}))

      "user:team-broadcast-response"
      (slack/web-api "views.open" {:auth/token (:slack/bot-token context)}
                     {:trigger_id (:trigger_id payload)
                      :view (hiccup/->blocks-json
                              (screens/team-broadcast-response (->> payload
                                                                    :message
                                                                    :blocks last
                                                                    :elements first
                                                                    ;; text between brackets with lookahead/lookbehind:
                                                                    :text (re-find #"(?<=\[).+?(?=\])"))))})

      "user:team-broadcast-response-status"
      (slack/web-api "views.update" {:auth/token (:slack/bot-token context)}
                     {:view_id view-id
                      :view (hiccup/->blocks-json
                              (screens/team-broadcast-response-status
                                (get-in payload [:view :private_metadata])))})
      "user:team-broadcast-response-achievement"
      (slack/web-api "views.update"
                     {:auth/token (:slack/bot-token context)} {:view_id view-id
                                                               :view (hiccup/->blocks-json
                                                                       (screens/team-broadcast-response-achievement
                                                                         (get-in payload [:view :private_metadata])))})
      "user:team-broadcast-response-help"
      (slack/web-api "views.update" {:auth/token (:slack/bot-token context)}
                     {:view_id view-id
                      :view (hiccup/->blocks-json
                              (screens/team-broadcast-response-help
                                (get-in payload [:view :private_metadata])))})

      "broadcast2:channel-select"                           ;; refresh same view then save selection in private metadata
      (slack/web-api "views.update" {:auth/token (:slack/bot-token context)}
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
      (slack/web-api "chat.postMessage" {:auth/token (:slack/bot-token context)}
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

(defn slack-context
  "Returns context-map expected by functions handling Slack events"
  [team-id user-id]
  {:pre [team-id user-id]}
  (let [app-id (-> env/config :slack :app-id)
        team (slack-db/linked-team team-id)
        user (slack-db/linked-user user-id)
        {:keys [bot-token bot-user-id]} (get-in team [:app (keyword app-id)])]
    {:slack/app-id app-id
     :slack/team-id team-id
     :slack/user-id user-id
     :slack/bot-token bot-token
     :slack/bot-user-id bot-user-id
     :sparkboard/account-id (:account-id user)
     :sparkboard/board-id (:board-id team)
     :sparkboard/jvm-root (-> env/config :sparkboard/jvm-root)
     :env (:env env/config "dev")}))

(defn update-user-home-tab! [context]
  (slack/web-api "views.publish" {:auth/token (:slack/bot-token context)}
                 {:user_id (:slack/user-id context)
                  :view (hiccup/->blocks-json (screens/home context))}))

(defn link-account! [context {:as params
                              :keys [slack/team-id
                                     slack/user-id
                                     sparkboard/account-id]}]
  ;; TODO
  ;; visible confirmation step to link the two accounts?
  (slack-db/link-user-to-account!                           ;; link the accounts
    {:slack/team-id team-id
     :slack/user-id user-id
     :sparkboard/account-id account-id})
  (update-user-home-tab! (assoc context :sparkboard/account-id account-id))
  (ring.http/ok))

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

(defn req-token [req]
  (or (some-> (:headers req) (get "authorization") (str/replace #"^Bearer: " ""))
      (-> req :params :token)))

(defn mock-slack-link-proxy [{{:as token-claims
                               :keys [slack/user-id
                                      slack/team-id
                                      sparkboard/board-id
                                      sparkboard/account-id
                                      redirect]} :auth/token-claims
                              :as req}]
  (when-not account-id
    (link-account! (slack-context team-id user-id)
                   (assoc token-claims :sparkboard/account-id "MOCK_SPARKBOARD_ACCOUNT")))
  (if (str/starts-with? redirect "http")
    {:status 302
     :headers {"Location" redirect}
     :body ""}
    {:body (str "mock redirect: " redirect)
     :status 200
     :headers {"Content-Type" "text/string"}}))

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
  (ring.http/ok))

(defn slack-interaction
  "Slack Interaction (e.g. global shortcut or modal) handler"
  [params]
  (try-future
    (let [payload (json->clj (:payload params))
          context (slack-context (-> payload :team :id)
                                 (-> payload :user :id))]
      (case (:type payload)
        ;; Slack "Global shortcut"
        "shortcut" (slack/web-api "views.open" {:auth/token (:slack/bot-token context)}
                                  {:trigger_id (:trigger_id payload)
                                   :view (hiccup/->blocks-json (screens/shortcut-modal context))})

        ;; ;; User acted on existing view
        "block_actions" (block-actions context payload)

        ;; "Submit" button pressed; modal submitted
        "view_submission" (submission context payload)

        ;; TODO throw?
        (log/error [:unhandled-event (:type payload)]))))
  (ring.http/ok))

(defn wrap-sparkboard-verify
  "must be a Sparkboard server request"
  [f & claims-checks]
  (fn [req]
    (if-let [claims (try (some-> (req-token req)
                                 (fire-tokens/decode)
                                 (cond-> (seq claims-checks)
                                         (u/guard (apply every-pred claims-checks))))
                         (catch Exception e nil))]
      (f (assoc req :auth/token-claims claims))
      (do
        (log/warn "Sparkboard token verification failed." {:uri (:uri req)})
        {:status 401
         :headers {"Content-Type" "text/plain"}
         :body "Invalid token"}))))

(defn slack-channel-namify [s]
  (-> s
      (str/replace #"\s+" "-")
      (str/lower-case)
      (str/replace #"[^\w\-_\d]" "")
      (as-> s (subs s 0 (min 80 (count s))))))

(defmacro log-call [form]
  ;;
  `(do (log/info :call/forms (quote ~(first form)) ~@(rest form))
       (let [v# ~form]
         (log/info :call/value v#)
         v#)))

(defn user-info [{:slack/keys [user-id bot-token]}]
  (http/get+ (str slack/base-uri "users.info")              ;; `web-api` fn does not work b/c queries are broken
             {:query {:user user-id
                      :token bot-token}}))

(defn find-or-create-channel [{:keys [sparkboard/board-id
                                      sparkboard/account-id
                                      sparkboard/project-id]}]
  (or (slack-db/project->linked-channel project-id)
      (let [{:keys [slack/team-id slack/team-name]} (slack-db/board->team board-id)
            {:keys [slack/user-id]} (slack-db/account->team-user {:slack/team-id team-id
                                                                  :sparkboard/account-id account-id})]

        ;; possible states:
        ;; - user is a member of the workspace, but the account is not yet linked
        ;;   ->
        ;; - user is not a member of the workspace
        ;;   -> send them to invite-link

        (if-not user-id
          {:status 200
           :body (str "You are not a member of " team-name ".")}
          (let [{:as context
                 :slack/keys [bot-token
                              bot-user-id
                              user-id]} (slack-context team-id user-id)
                domain (slack-db/board-domain board-id)
                project-url (str (urls/sparkboard-host domain) "/project/" project-id)
                {project-id :_id
                 project-title :title} (http/get+ (str project-url "/json"))
                channel-id (-> (log-call (slack/web-api "conversations.create"
                                                        {:auth/token bot-token}
                                                        {:user_ids [bot-user-id] ;; adding user-id here does not work
                                                         :is_private false
                                                         :name (slack-channel-namify (str "team-" project-title))}))
                               :channel
                               :id)]
            (log-call (slack/web-api "conversations.invite"
                                     {:auth/token bot-token}
                                     {:channel channel-id
                                      :users [user-id]}))
            (log-call (slack/web-api "conversations.setTopic"
                                     {:auth/token bot-token}
                                     {:channel channel-id
                                      :topic (str "on Sparkboard: " project-url)}))
            (log-call (slack-db/link-channel-to-project! {:slack/team-id team-id
                                                          :slack/channel-id channel-id
                                                          :sparkboard/project-id project-id}))
            (assoc context :slack/channel-id channel-id))))))

(defn nav-project-channel [{:as req
                            {:sparkboard/keys [account-id
                                               board-id]} :auth/token-claims
                            {:keys [project-id]} :params}]
  (let [{:slack/keys [channel-id
                      team-id]} (find-or-create-channel {:sparkboard/board-id board-id
                                                         :sparkboard/account-id account-id
                                                         :sparkboard/project-id project-id})]
    (log/trace :nav-to-channel channel-id)
    (ring.http/found (urls/app-redirect {:app (-> env/config :slack :app-id)
                                         :team team-id
                                         :channel channel-id}))))

(defn hash-hmac256 [secret message]
  (-> (Mac/getInstance "HmacSHA256")
      (doto (.init (SecretKeySpec. (.getBytes secret "UTF-8") "HmacSHA256")))
      (.doFinal (.getBytes message "UTF-8"))
      (Hex/encodeHexString)))

(defn peek-body-string
  "Returns req with :body-string, and replaces :body with a new unread input stream"
  [req]
  (let [body-string (slurp (:body req)
                      :encoding (or (ring.util.request/character-encoding req)
                                    "UTF-8"))]
    (assoc req :body (io/input-stream (.getBytes body-string))
               :body-string body-string)))

(defn wrap-slack-verify
  "Verifies requests containing `x-slack-signature` header.
   Must eval before ring formatting middleware (to access body input-stream)"
  [f]
  ;; https://api.slack.com/authentication/verifying-requests-from-slack
  (fn [{:as req :keys [headers]}]
    (if-let [x-slack-signature (headers "x-slack-signature")]
      (let [{:as req :keys [body-string]} (peek-body-string req)
            secret (-> env/config :slack :signing-secret)
            message (str "v0:" (headers "x-slack-request-timestamp") ":" body-string)
            hashed (str "v0=" (hash-hmac256 secret message))]
        (if (= hashed x-slack-signature)
          (f req)
          (do
            (log/warn "Slack verification failed. Possible causes: bad signing secret in env/config, attack.")
            (ring.http/unauthorized))))
      (f req))))

(defn sparkboard-action
  "Event triggered by Sparkboard (legacy)"
  [{:as req :keys [params]}]
  (let [{:keys [action slack/team-id slack/user-id]} params
        context (slack-context team-id user-id)]
    (case action
      :update-home! (update-user-home-tab! context)
      :link-account! (link-account! context params)
      :create-linked-channel! (create-linked-channel! context params))))

(def routes
  ["/" (merge {"slack-api" (fn [{:as req :keys [params]}]
                             (log/trace "[slack-api]" req)
                             (cond (:challenge params) (ring.http/ok (:challenge params))
                                   (:event params) (slack-event params)
                                   (:payload params) (slack-interaction params)
                                   :else (log/error [:unhandled-request req])))
               "slack-api/oauth-redirect" slack-oauth/redirect
               "slack/server-action" (wrap-sparkboard-verify sparkboard-action
                                                             :sparkboard/server-request?)
               ["slack/project-channel/" :project-id] (wrap-sparkboard-verify nav-project-channel
                                                                              :sparkboard/account-id
                                                                              :sparkboard/board-id)
               "slack/install" slack-oauth/install-redirect}
              (when (env/config :dev/mock-sparkboard? true)
                {"mock/" {"slack-link" (wrap-sparkboard-verify mock-slack-link-proxy)}}))])

(def app
  (-> (bidi.ring/make-handler routes)
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)
      (ring.middleware.format/wrap-restful-format {:formats [:json-kw :transit-json]})
      wrap-slack-verify
      ((fn [f] (fn [req] (log/info :URI (:uri req)) (f req))))))

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
  (-main))
