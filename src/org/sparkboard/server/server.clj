(ns org.sparkboard.server.server
  "HTTP server handling Slack requests"
  (:gen-class)
  (:require [bidi.ring :as bidi.ring]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [lambdaisland.uri :as uri]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.firebase.tokens :as fire-tokens]
            [org.sparkboard.http :as http]
            [org.sparkboard.js-convert :refer [json->clj clj->json]]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.server.slack.core :as slack]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.server.slack.screens :as screens]
            [org.sparkboard.slack.oauth :as slack-oauth]
            [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.slack.urls :as urls]
            [org.sparkboard.slack.view :as view]
            [org.sparkboard.transit :as transit]
            [org.sparkboard.util :as u]
            [org.sparkboard.util.future :refer [try-future]]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.util.http-response :as ring.http]
            [ring.util.mime-type :as ring.mime-type]
            [ring.util.request]
            [ring.util.response :as ring.response]
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

(defn team-context [team-id]
  {:pre [team-id]}
  ;; context we derive from team-id
  (let [app-id (-> env/config :slack :app-id)
        team (slack-db/linked-team team-id)
        {:keys [bot-token bot-user-id]} (get-in team [:app (keyword app-id)])]
    {:slack/team-id team-id
     :slack/team team
     :slack/team-domain (:domain (slack/team-info bot-token team-id))
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
  (let [context (merge (team-context team-id)
                       (user-context user-id)
                       {:slack/app-id (-> env/config :slack :app-id)
                        :sparkboard/jvm-root (-> env/config :sparkboard/jvm-root)
                        :env (:env env/config "dev")})]
    (log/debug "[slack-context]:" context)
    context))

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

(defn request-updates [context {:as opts
                                :keys [message
                                       response-channel
                                       collect-in-thread]}]
  (log/info :request-updates opts)
  (let [broadcast-ref (.push (fire-jvm/->ref "/slack-broadcast"))
        {thread :ts} (when response-channel
                       (slack/web-api "chat.postMessage" {:auth/token (:slack/bot-token context)}
                                      {:channel response-channel
                                       :blocks (hiccup/->blocks-json
                                                 [[:section
                                                   (str "*Team Broadcast Sent:*\n"
                                                        (view/blockquote message))]])}))
        blocks (hiccup/->blocks-json
                 (screens/team-broadcast-message (.getKey broadcast-ref) opts))]
    (fire-jvm/set-value broadcast-ref
                        {:message message
                         :response-channel response-channel
                         :response-thread (when collect-in-thread thread)})
    (mapv #(slack/web-api "chat.postMessage"
                          {:auth/token (:slack/bot-token context)}
                          {:channel (:channel-id %)
                           :blocks blocks})
          (slack-db/team->all-linked-channels (:slack/team-id context)))))

(defn update-user-home-tab! [context]
  (slack/web-api "views.publish"
                 {:user_id (:slack/user-id context)
                  :view (hiccup/->blocks-json
                          (screens/home context))}))

(defn link-account! [{:keys [slack/team-id
                             slack/user-id
                             sparkboard/account-id]}]
  (let [context (slack-context team-id user-id)]
    ;; TODO
    ;; visible confirmation step to link the two accounts?
    (slack-db/link-user-to-account!                         ;; link the accounts
      {:slack/team-id team-id
       :slack/user-id user-id
       :sparkboard/account-id account-id})
    (update-user-home-tab! (assoc context :sparkboard/account-id account-id))
    (message-user! context [[:section
                             (str (screens/mention (:slack/user-id context))
                                  " "
                                  (screens/team-message context :welcome-confirmation))]])
    (ring.http/found
      (str "/slack/link-complete?"
           (uri/map->query-string {:slack (urls/app-redirect {:app (:slack/app-id context)
                                                              :team team-id
                                                              :domain (:slack/team-domain context)})
                                   :sparkboard (-> (slack-db/board-domain (:sparkboard/board-id context))
                                                   (urls/sparkboard-host))})))))

(defn link-account-for-new-user [context]
  (if-let [account-id (some-> context :slack/event :user :profile :email fire-jvm/email->uid)]
    (link-account! (assoc context :sparkboard/account-id account-id))
    (message-user! context
                   [[:section
                     (str (screens/mention (:slack/user-id context))
                          " "
                          (screens/team-message context :welcome))]
                    [:actions
                     [:button {:style "primary"
                               :url (urls/link-sparkboard-account context)}
                      "Link Sparkboard Account"]]
                    [:context
                     [:plain_text
                      (str "Welcome here! Please click to link your account with Sparkboard, "
                           "where we keep track of all active projects.")]]])))

(defn mock-slack-link-proxy [{{:as token-claims
                               :keys [slack/user-id
                                      slack/team-id
                                      sparkboard/board-id
                                      sparkboard/account-id
                                      redirect]} :auth/token-claims
                              :as req}]
  (when-not account-id
    (link-account! (merge (slack-context team-id user-id)
                          token-claims
                          {:sparkboard/account-id "MOCK_SPARKBOARD_ACCOUNT"})))
  (if (str/starts-with? redirect "http")
    (ring.http/found redirect)
    {:body (str "mock redirect: " redirect)
     :status 200
     :headers {"Content-Type" "text/string"}}))
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
                               slack/team-name
                               sparkboard/account-id
                               redirect]}]
  (ring.http/found
    (str "/slack/invite-offer?"
         (uri/map->query-string
           {:custom-token (fire-jvm/custom-token account-id)
            :team-id team-id
            :invite-link invite-link
            :team-domain team-domain
            :team-name team-name
            :redirect-encoded (uri/query-encode redirect)}))))

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
        (if invite-link
          (invite-user req (merge slack-team
                                  slack-user
                                  token-claims
                                  (team-context team-id)
                                  {:redirect (str (:uri req) "?" (:query-string req))}))
          (return-html 200
                       (let [domain (:slack/team-domain (team-context team-id))]
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
                                      :topic (view/link "View on Sparkboard" project-url)}))
            (log-call (slack-db/link-channel-to-project! {:slack/team-id team-id
                                                          :slack/channel-id channel-id
                                                          :sparkboard/project-id project-id}))
            (assoc context :slack/channel-id channel-id))))))

(defn project-channel-deep-link [{:keys [slack/team-id
                                         slack/channel-id]}]
  (urls/app-redirect {:app (-> env/config :slack :app-id)
                      :domain (:slack/team-domain (team-context team-id))
                      :team team-id
                      :channel channel-id}))

(defn nav-project-channel [{:as req
                            {:sparkboard/keys [account-id
                                               board-id]} :auth/token-claims
                            {:keys [project-id
                                    project-title]} :params}]
  (ring.http/found
    (-> (find-or-create-channel #:sparkboard{:board-id board-id
                                             :account-id account-id
                                             :project-id project-id
                                             :project-title project-title})
        (project-channel-deep-link))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn slack-api-handlers []
  (merge
    screens/checks-test
    screens/multi-select-modal
    {:shortcut/UNSET
     ;; TODO existing shortcuts don't have callback_id set?
     (fn [context]
       (slack/web-api "views.open" {:auth/token (:slack/bot-token context)}
                      {:trigger_id (-> context :slack/payload :trigger_id)
                       :view (hiccup/->blocks-json (screens/shortcut-modal context))}))
     :event/app_home_opened
     (fn [context]
       (case (-> context :slack/event :tab)
         "home" (update-user-home-tab! context)
         nil))

     :event/team_join link-account-for-new-user

     :view_submission/team-broadcast-modal-compose
     (fn [context]
       (let [values (view/view-values (-> context :slack/payload :view))]
         (request-updates context
                          {:message (decode-text-input (:broadcast2:text-input values))
                           :response-channel (:broadcast2:channel-select values)
                           :collect-in-thread (contains? (:broadcast-options values) "collect-in-thread")})))

     ;; User broadcast response: describe current status
     :view_submission/team-broadcast-response
     (fn [context]
       (let [values (view/view-values (-> context :slack/payload :view))]
         (log/info :broadcast-response-values values)
         (let [{:keys [channel]
                {:keys [ts]} :message} (slack/web-api "chat.postMessage"
                                                      (let [reply-ref-path (:broadcast/reply-path values)
                                                            broadcast (fire-jvm/read (.getParent (.getParent (fire-jvm/->ref reply-ref-path))))]
                                                        {:blocks (hiccup/->blocks-json
                                                                   (screens/team-broadcast-response-msg
                                                                     (:slack/user-id context)
                                                                     (-> reply-ref-path fire-jvm/read :from-channel-id)
                                                                     (decode-text-input (:user:status-input values))))
                                                         :channel (-> broadcast :response-channel)
                                                         :thread_ts (-> broadcast :response-thread)}))
               {:keys [permalink]} (slack/web-api "chat.getPermalink" {:channel channel
                                                                       :message_ts ts})]
           (slack/web-api "chat.postMessage"
                          (let [{:keys [broadcast/reply-ts
                                        broadcast/reply-channel]} values]
                            {:channel reply-channel
                             :ts reply-ts
                             :blocks (hiccup/->blocks-json
                                       [[:section (str "Thanks for your response! " (view/link "See what you wrote." permalink))]])})))))

     :view_submission/invite-link-modal
     (fn [context]
       (let [values (view/view-values (-> context :slack/payload :view))]
         (fire-jvm/set-value (str "/slack-team/" (:slack/team-id context) "/invite-link/")
                             (:invite-link-input values))
         (update-user-home-tab!
           ;; update team-context to propagate invite-link change
           (merge context (team-context (:slack/team-id context))))))

     :view_submission/customize-messages-modal
     (fn [context]
       (let [values (view/view-values (-> context :slack/payload :view))]
         (fire-jvm/update-value (str "/slack-team/" (:slack/team-id context) "/custom-messages") values)))

     :block_actions/admin:customize-messages-modal-open
     (fn [context]
       (slack/views-open (screens/customize-messages-modal context)))

     :block_actions/admin:invite-link-modal-open
     (fn [context]
       (slack/views-open (screens/invite-link-modal context)))

     :block_actions/admin:team-broadcast
     (fn [context]
       (let [view (-> context :slack/payload :view)]
         (case (:type view)
           "home" (slack/views-open (screens/team-broadcast-modal-compose context))
           "modal" (slack/views-update (:id view) (screens/team-broadcast-modal-compose context)))))

     :block_actions/user:team-broadcast-response
     (fn [{:keys [slack/payload]}]
       ; "Post an Update" button (user opens modal to respond to broadcast)

       (let [broadcast-id (-> payload :actions first :block_id transit/read :broadcast/firebase-key)
             broadcast-path (str "/slack-broadcast/" broadcast-id)
             reply-ref (.push (fire-jvm/->ref (str broadcast-path "/replies")))
             reply-path (str broadcast-path "/replies/" (.getKey reply-ref))]
         (fire-jvm/set-value reply-ref {:from-channel-id (-> payload :channel :id)})
         (slack/views-open (screens/team-broadcast-response
                             (-> payload :message :blocks first :text :text) ; broadcast msg
                             {:broadcast/reply-path reply-path
                              :broadcast/reply-ts (-> payload :message :ts)
                              :broadcast/reply-channel (-> payload :channel :id)}))))}))

(defn handle-slack-api-request [{:as params :keys [event payload challenge]} handlers]
  (log/trace "[slack-api]" params)
  (if challenge
    (ring.http/ok challenge)
    (do
      (try-future
        (let [[handler-id context]
              (cond event
                    [(keyword "event" (:type event))
                     (-> (slack-context (:team_id params) (if (map? (:user event)) (:id (:user event)) (:user event)))
                         (assoc :slack/event event))]

                    payload
                    (let [payload (-> (json->clj payload)
                                      (update-in [:view :private_metadata]
                                                 #(if (str/blank? %) {} (transit/read %))))
                          context (-> (slack-context (-> payload :team :id) (-> payload :user :id))
                                      (assoc :slack/trigger-id (:trigger_id payload)
                                             :slack/view-hash (-> payload :view :hash)))
                          handler-id (keyword (:type payload)
                                              (case (:type payload)
                                                "shortcut" (:callback_id payload "UNSET")
                                                ("view_submission" "view_closed") (-> payload :view :callback_id)
                                                "block_actions" (-> payload :actions first :action_id)))]
                      [handler-id (assoc context :slack/payload payload)]))]
          (log/debug handler-id context)
          (def LAST-CONTEXT context)
          (binding [slack/*request* context]
            ((handlers handler-id (fn [& args] (log/error :unhandled-request handler-id args)))
             (assoc context ::handler-id handler-id)))))
      (ring.http/ok))))

(def routes
  ["/" (merge {"slack-api" (fn [{:as req :keys [params]}]
                             (handle-slack-api-request params (slack-api-handlers)))
               "slack-api/oauth-redirect" #'slack-oauth/redirect
               "slack/" {"link-account" (wrap-sparkboard-verify (comp link-account! :auth/token-claims)
                                                                :sparkboard/server-request?)
                         ["project-channel/" :project-id] (-> nav-project-channel
                                                              (wrap-sparkboard-invite)
                                                              (wrap-sparkboard-verify :sparkboard/account-id
                                                                                      :sparkboard/board-id))
                         "install" #'slack-oauth/install-redirect
                         "app-redirect" (fn [{{:as query :keys [team]} :params}]
                                          (assert team "Slack team not provided")
                                          (ring.http/found
                                            (urls/app-redirect
                                              (assoc query
                                                :app (-> env/config :slack :app-id)
                                                :domain (:slack/team-domain (team-context team))))))
                         "reinstall" {"" (fn [req]
                                           (ring.http/found (urls/install-slack-app {:reinstall? true})))
                                      ["/" :team-id] (fn [{{:keys [team-id]} :params}]
                                                       (ring.http/found (urls/install-slack-app {:slack/team-id team-id})))}}}
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
           (log/error (ex-message e)
                      (ex-data e)
                      (ex-cause e))
           (return-text
             (:status (ex-data e) 500)
             (ex-message e)))
         (catch java.lang.AssertionError e
           (log/error (ex-message e)
                      (ex-data e)
                      (ex-cause e))
           (log/error e)
           (return-text
             (:status (ex-data e) 500)
             (ex-message e))))))

(defn public-resource [path]
  (some-> (ring.response/resource-response path {:root "public"})
          (ring.response/content-type (ring.mime-type/ext-mime-type path))))

(def single-page-app-html
  (delay (let [body (-> (io/resource "public/index.html")
                        (slurp)
                        (str/replace "SPARKBOARD_CONFIG_TEXT" env/client-config)
                        (str/replace "app.js" (str "app.js?v=" (.getTime (java.util.Date.)))))]
           {:status 200
            :headers {"Content-Type" "text/html"
                      "Content-Length" (str (count body))}
            :body body})))

(defn wrap-static-first [f]
  ;; serve static files before all the other middleware, logging, etc.
  (fn [req]
    (or (public-resource (:uri req))
        (f req))))

(def wrap-static-fallback
  ;; fall back to index.html -- TODO: re-use the client router to serve 404s for unknown paths
  (fn [f]
    (fn [req]
      (or (f req)
          @single-page-app-html))))

(def app
  (-> (bidi.ring/make-handler routes)
      wrap-static-fallback
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)
      (ring.middleware.format/wrap-restful-format {:formats [:json-kw :transit-json]})
      wrap-slack-verify
      wrap-handle-errors
      wrap-static-first))

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
