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
  (or (env/config :dev.logging/levels)
      (case (env/config :env)
        "staging" '{:all :info
                    org.sparkboard.slack.oauth :trace
                    org.sparkboard.server.server :trace}
        "prod" {:all :warn}
        '{:all :info})))

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

(defn block-actions
  "Handler for specific block actions.
  Branches on the bespoke action ID (set in server.slack.screens)."
  [context payload]
  (log/debug "[block-actions] payload" payload)
  (let [view-id (get-in payload [:container :view_id])
        [action-id firebase-key] (str/split (-> payload :actions first :action_id)
                                            (re-pattern screens/action-id-separator))
        modal! (fn
                 ([blocks]
                  (slack/web-api "views.open"
                                 {:auth/token (:slack/bot-token context)}
                                 {:trigger_id (:trigger_id payload)
                                  :view (hiccup/->blocks-json blocks)}))
                 ([view-id blocks]
                  (slack/web-api "views.update" {:auth/token (:slack/bot-token context)}
                                 {:view_id view-id
                                  :view (hiccup/->blocks-json blocks)})))]
    (case action-id
      "admin:customize-messages-modal-open" (modal! (screens/customize-messages-modal context))
      "admin:invite-link-modal-open" (modal! (screens/invite-link-modal context))
      "admin:team-broadcast"
      (case (get-in payload [:view :type])
        "home" (modal! (screens/team-broadcast-modal-compose context))
        "modal" (modal! view-id (screens/team-broadcast-modal-compose context)))

      "user:team-broadcast-response"                        ; "Post an Update" button (user opens modal to respond to broadcast)
      (let [firebase-path (str "/slack-broadcast/" firebase-key)
            firebase-child-ref (.push (fire-jvm/->ref (str firebase-path "/replies")))]
        (fire-jvm/set-value firebase-child-ref
                            {:responding-from-channel (-> payload :channel :name)})
        (modal! (screens/team-broadcast-response
                  (-> payload :message :blocks first :text :text) ; broadcast msg
                  (str firebase-path "/replies/" (.getKey firebase-child-ref)))))

      "broadcast2:channel-select"                           ; refresh same view then save selection in private metadata
      (modal! view-id (screens/team-broadcast-modal-compose context (-> payload :actions first :selected_conversation)))

      ;; Default: failure XXX `throw`?
      (log/error [:unhandled-block-action (-> payload :actions first :action-id)]))))

(defn team-context [team-id]
  ;; context we derive from team-id
  (let [app-id (-> env/config :slack :app-id)
        team (slack-db/linked-team team-id)
        {:keys [bot-token bot-user-id]} (get-in team [:app (keyword app-id)])]
    (def APP-ID app-id)
    (def BOT-TOKEN bot-token)
    (def BOT-USER-ID bot-user-id)
    {:slack/team-id team-id
     :slack/team team
     :slack/bot-token bot-token
     :slack/bot-user-id bot-user-id
     :slack/invite-link (:invite-link team)
     :sparkboard/board-id (:board-id team)}))

(defn user-context [user-id]
  ;; context we derive from user-id
  (let [user (slack-db/linked-user user-id)]
    {:sparkboard/account-id (:account-id user)
     :slack/user-id user-id}))

(defn slack-context
  "Returns context-map expected by functions handling Slack events"
  [team-id user-id]
  {:pre [team-id user-id (string? user-id)]}
  (merge (team-context team-id)
         (user-context user-id)
         {:slack/app-id (-> env/config :slack :app-id)
          :sparkboard/jvm-root (-> env/config :sparkboard/jvm-root)
          :env (:env env/config "dev")}))

(defn req-token [req]
  (or (some-> (:headers req) (get "authorization") (str/replace #"^Bearer: " ""))
      (-> req :params :token)))

;; TODO
;; return nicely formatted pages
(defn return-text [status message]
  {:status status
   :headers {"Content-Type" "text/plain"}
   :body message})

(defn return-html [status message]
  {:status status
   :headers {"Content-Type" "text/html"}
   :body message})

(comment
  ;; I thought this would be a nicer way to send welcome messages,
  ;; but it doesn't work (I think because the user doesn't yet have
  ;; an active session when the team_join event fires)
  (defn message-ephemeral! [context channel blocks]
    (slack/web-api "chat.postEphemeral"
                   {:auth/token (:slack/bot-token context)}
                   {:channel channel
                    :user (:slack/user-id context)
                    :blocks (hiccup/->blocks-json blocks)})))

(defn message-user! [context blocks]
  (slack/web-api "chat.postMessage"
                 {:auth/token (:slack/bot-token context)}
                 {:channel (:slack/user-id context)
                  :blocks (hiccup/->blocks-json blocks)}))

(defn mention [user-id]
  (str "<@" user-id "> "))

(defn slack-ok! [resp status message]
  (if (:ok resp)
    resp
    (throw (ex-info message {:status status
                             :resp resp}))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Individual/second-tier handlers

(defn request-updates [context msg reply-channel]
  (let [reply-channel-name (:name_normalized (slack/channel-info reply-channel (:slack/bot-token context)))
        firebase-ref (.push (fire-jvm/->ref "/slack-broadcast"))]
    (fire-jvm/set-value firebase-ref
                        {:message msg
                         :reply-to {:channel-id reply-channel
                                    :reply-channel reply-channel-name}})
    (mapv #(slack/web-api "chat.postMessage"
                          {:auth/token (:slack/bot-token context)}
                          {:channel (:channel-id %)
                           :blocks (hiccup/->blocks-json
                                     (screens/team-broadcast-message msg
                                                                     (.getKey firebase-ref)
                                                                     reply-channel-name))})
          (slack-db/team->all-linked-channels (:slack/team-id context)))))

(defn update-user-home-tab! [context]
  (slack/web-api "views.publish" {:auth/token (:slack/bot-token context)}
                 {:user_id (:slack/user-id context)
                  :view (hiccup/->blocks-json (screens/home context))}))

(defn submission
  "Handler for 'Submit' press on any Slack modal."
  [context payload]
  (log/debug "[submission!] payload:" payload)
  (let [state (get-in payload [:view :state :values])
        action-values (->> (apply merge (vals state))
                           (reduce-kv (fn [m k {:keys [value]}] (assoc m k value)) {}))
        callback-id (-> payload :view :callback_id)]
    (log/trace :action-values action-values)
    (case callback-id
      ;; Admin broadcast: request for project update
      "team-broadcast-modal-compose"
      (request-updates context
                       (decode-text-input (:broadcast2:text-input action-values))
                       (get-in payload [:view :private_metadata]))

      ;; User broadcast response: describe current status
      "team-broadcast-response"
      (slack/web-api "chat.postMessage" {:auth/token (:slack/bot-token context)}
                     (let [firebase-key (get-in payload [:view :private_metadata])]
                       {:blocks (hiccup/->blocks-json
                                  (screens/team-broadcast-response-msg
                                    (-> payload :user :name)
                                    (-> firebase-key fire-jvm/read :responding-from-channel)
                                    (-> (or (get-in state [:sb-project-status1 :user:status-input])
                                            (get-in state [:sb-project-achievement1 :user:achievement-input])
                                            (get-in state [:sb-project-help1 :user:help-input]))
                                        :value
                                        decode-text-input)))
                        :channel (:channel-id (:reply-to (fire-jvm/read (.getParent (.getParent (fire-jvm/->ref firebase-key))))))}))
      "invite-link-modal"
      (do
        (fire-jvm/set-value (str "/slack-team/" (:slack/team-id context) "/invite-link/")
                            (:invite-link-input action-values))
        (update-user-home-tab!
          ;; update team-context to propagate invite-link change
          (merge context (team-context (:slack/team-id context)))))
      "customize-messages-modal"
      (fire-jvm/update-value (str "/slack-team/" (:slack/team-id context) "/custom-messages") action-values)
      (log/error "Unhandled modal submission" callback-id))))

(defn send-welcome-message! [context]
  (message-user! context
                 [[:section
                   (str (mention (:slack/user-id context))
                        " "
                        (screens/team-message context :welcome))]
                  [:actions
                   [:button {:style "primary"
                             :url (urls/link-sparkboard-account context)}
                    "Connect to Sparkboard"]]
                  [:context
                   [:plain_text
                    "This is a link to sparkboard.com, where you can register or sign in to link your account."]]]))

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
  (message-user! context [[:section
                           (str (mention (:slack/user-id context))
                                " "
                                (screens/team-message context :welcome-confirmation))]])
  (ring.http/ok))

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
    (ring.http/found redirect)
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
          user-id (if (map? (:user evt)) (:id (:user evt)) (:user evt))
          context (slack-context (:team_id params) user-id)]
      (log/debug "[event] evt:" evt)
      (log/debug "[event] context:" context)
      (case (get evt :type)
        "app_home_opened" (case (:tab evt)
                            "home" (update-user-home-tab! context)
                            nil)
        "team_join" (send-welcome-message! context)
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Middleware

(defn wrap-sparkboard-verify
  "must be a Sparkboard server request"
  [f & claims-checks]
  (fn [req]
    (let [token (req-token req)
          claims (some-> token (fire-tokens/decode))
          check-claims (or (some->> claims-checks seq (apply every-pred)) (constantly true))]
      (if (check-claims claims)
        (f (assoc req :auth/token-claims claims))
        (do
          (log/warn "Sparkboard token verification failed." {:uri (:uri req)
                                                             :claims claims
                                                             :token token})
          (return-text 401 "Invalid token"))))))

(defn invite-user [req {:keys [slack/team-id
                               slack/invite-link
                               slack/team-domain
                               sparkboard/account-id]}]
  ;; TODO - this page can subscribe to /account/$/slack-team/$/user-id,
  ;;        wait for linking to occur,
  ;;        and then show a "Continue" button ... ?
  ;;        (sign user in to firebase by creating a custom token, & passing it to the page)
  (return-html 200
               (str "<p>"
                    "You've been invited to <b>" team-domain "</b> on Slack. Please <a href='" invite-link "'>accept the invitation</a> to continue, then follow the instructions to link your Sparkboard account."
                    "</p>"
                    "<p>(If you already have a <a href='https://" team-domain ".slack.com'>" team-domain ".slack.com</a> account, link your account by clicking the Sparkboard app in the sidebar.)</p>")))

(defn wrap-sparkboard-invite [f]
  (fn [req]
    (let [{:as token-claims
           :sparkboard/keys [account-id board-id]} (:auth/token-claims req)
          {:as slack-team
           :slack/keys [team-id invite-link]} (slack-db/board->team board-id)
          {:as slack-user
           :keys [slack/user-id]} (slack-db/account->team-user {:slack/team-id team-id
                                                                :sparkboard/account-id account-id})]
      (if user-id
        (f req)
        (let [{:keys [slack/bot-token]} (team-context team-id)
              domain (-> (http/get+ (str slack/base-uri "team.info")
                                    {:query {:token bot-token
                                             :team "T014098L9FD"}})
                         (slack-ok! 500 "Could not read team info")
                         :team :domain)]
          (if invite-link
            (invite-user req (merge slack-team
                                    slack-user
                                    token-claims
                                    {:slack/team-domain domain}))
            (return-html 200
                         (str "You need an invite link to join <a href='https://" domain ".slack.com'>" domain ".slack.com</a>. "
                              "Please contact an organizer."))))))))

(defn find-or-create-channel [{:sparkboard/keys [board-id
                                                 account-id
                                                 project-id
                                                 project-title]}]
  (or (slack-db/project->linked-channel project-id)
      (let [{:keys [slack/team-id slack/team-name]} (slack-db/board->team board-id)
            {:keys [slack/user-id]} (slack-db/account->team-user {:slack/team-id team-id
                                                                  :sparkboard/account-id account-id})
            project-title (slack-channel-namify (str "project-" project-title))]
        (assert (not (str/blank? project-title)) "Project title is required")
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
                channel-id (-> (log-call (slack/web-api "conversations.create"
                                                        {:auth/token bot-token}
                                                        {:user_ids [bot-user-id] ;; adding user-id here does not work
                                                         :is_private false
                                                         :name project-title}))
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
                            {:keys [project-id
                                    project-title]} :params}]
  (let [{:slack/keys [channel-id
                      team-id]} (find-or-create-channel #:sparkboard{:board-id board-id
                                                                     :account-id account-id
                                                                     :project-id project-id
                                                                     :project-title project-title})]
    (log/trace :nav-to-channel channel-id)
    (ring.http/found
      (urls/app-redirect {:app (-> env/config :slack :app-id)
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
      :link-account! (link-account! context params))))

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
               ["slack/project-channel/" :project-id] (-> nav-project-channel
                                                          (wrap-sparkboard-invite)
                                                          (wrap-sparkboard-verify :sparkboard/account-id
                                                                                  :sparkboard/board-id))
               "slack/install" slack-oauth/install-redirect}
              (when (not= "prod" (env/config :env))
                {"slack/install-local"
                 (fn [req] (ring.http/found (urls/install-slack-app {:dev/local? true})))})
              (when (env/config :dev/mock-sparkboard? true)
                {["mock/" :domain] {"/slack-link" (wrap-sparkboard-verify mock-slack-link-proxy)
                                    [[#".*" :catchall]]
                                    (fn [{:keys [params]}] {:body (str "Mock page for " (:domain params) "/" (:catchall params))
                                                            :status 200
                                                            :headers {"Content-Type" "text/plain"}})}}))])

(comment
  (fire-tokens/decode token))

(defn wrap-handle-errors [f]
  (fn [req]
    (log/info :URI (:uri req))
    (try (f req)
         (catch Exception e
           (log/error (ex-message e) (ex-data e))
           (return-text
             (:status (ex-data e) 500)
             (ex-message e)))
         (catch java.lang.AssertionError e
           (log/error (ex-message e) (ex-data e))
           (return-text
             (:status (ex-data e) 500)
             (ex-message e))))))

(def app
  (-> (bidi.ring/make-handler routes)
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)
      (ring.middleware.format/wrap-restful-format {:formats [:json-kw :transit-json]})
      wrap-slack-verify
      wrap-handle-errors))

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

  )
