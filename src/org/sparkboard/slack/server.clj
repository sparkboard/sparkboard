(ns org.sparkboard.slack.server
  "HTTP routes for Slack integration"
  (:require
    [bidi.bidi]
    [bidi.ring]
    [clojure.java.io :as io]
    [clojure.string :as str]
    [cognitect.transit :as transit]
    [lambdaisland.uri :as uri]
    [org.sparkboard.slack.firebase.jvm :as fire-jvm]
    [sb.server.env :as env]
    [org.sparkboard.slack.db :as slack.db]
    [org.sparkboard.slack.firebase.tokens :as fire-tokens]
    [org.sparkboard.slack.oauth :as slack-oauth]
    [org.sparkboard.slack.requests :as slack]
    [org.sparkboard.slack.screens :as screens]
    [org.sparkboard.slack.urls :as urls]
    [ring.middleware.defaults]
    [ring.middleware.format]
    [ring.util.http-response :as ring.http]
    [taoensso.timbre :as log]
    [sb.js-convert :refer [json->clj]]
    [org.sparkboard.slack.api :as slack.api]
    [org.sparkboard.slack.view :as v])
  (:import (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (org.apache.commons.codec.binary Hex)))

(defn req-auth-token [req]
  (or (some-> (:headers req) (get "authorization") (str/replace #"^Bearer: " ""))
      (-> req :params :token)))

(defn wrap-sparkboard-verify
  "must be a Sparkboard server request"
  [f & claims-checks]
  (fn [req]
    (let [token        (req-auth-token req)
          claims       (some-> token (fire-tokens/decode))
          check-claims (or (some->> claims-checks seq (apply every-pred)) (constantly true))]
      (if (check-claims claims)
        (f (assoc req :auth/token-claims claims))
        (do
          (log/warn "Sparkboard token verification failed." {:uri    (:uri req)
                                                             :claims claims
                                                             :token  token})
          {:status  401
           :headers {"Content-Type" "text/plain"}
           :body    "Invalid token"})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Individual/second-tier handlers

(defn link-account! [{:keys [slack/team-id
                             slack/user-id
                             sparkboard/account-id]}]
  (let [context (slack.db/slack-context team-id user-id)]
    ;; TODO
    ;; visible confirmation step to link the two accounts?
    (slack.db/link-user-to-account! ;; link the accounts
     {:slack/team-id team-id
      :slack/user-id user-id
      :sparkboard/account-id account-id})
    (v/home! screens/home (assoc context :sparkboard/account-id account-id) user-id)
    (slack/message-user! context
                         [[:section
                           (str (v/mention (:slack/user-id context)) " "
                                (screens/team-message context :welcome-confirmation))]])
    (ring.http/found
     (str "/slack/link-complete?"
          (uri/map->query-string {:slack (urls/app-redirect {:app (:slack/app-id context)
                                                             :team team-id
                                                             :domain (:slack/team-domain context)})
                                  :sparkboard (-> (slack.db/board-domain (:sparkboard/board-id context))
                                                  (urls/sparkboard-host))})))))

(defn link-account-for-new-user [context]
  (if-let [account-id (some-> context :slack/event :user :profile :email fire-jvm/email->uid)]
    (link-account! (assoc context :sparkboard/account-id account-id))
    (slack/message-user! context
                         [[:section
                           (str (v/mention (:slack/user-id context))
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
    (link-account! (merge (slack.db/slack-context team-id user-id)
                          token-claims
                          {:sparkboard/account-id "MOCK_SPARKBOARD_ACCOUNT"})))
  (if (str/starts-with? redirect "http")
    (ring.http/found redirect)
    {:body (str "mock redirect: " redirect)
     :status 200
     :headers {"Content-Type" "text/string"}}))

(v/register-handlers! {:shortcut/main-menu (partial v/open! screens/shortcut-modal)
                       :event/app_home_opened (partial v/handle-home-opened! screens/home)
                       :event/team_join link-account-for-new-user})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Utility fns

(defmacro spy-args [form]
  ;;
  `(do (log/trace :call/forms (quote ~(first form)) ~@(rest form))
       (let [v# ~form]
         (log/trace :call/value v#)
         v#)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Middleware

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
           :slack/keys [team-id invite-link]} (slack.db/board->team board-id)
          {:as slack-user
           :keys [slack/user-id]} (slack.db/account->team-user {:slack/team-id team-id
                                                                :sparkboard/account-id account-id})]
      (if user-id
        (f req)
        (if invite-link
          (invite-user req (merge slack-team
                                  slack-user
                                  token-claims
                                  (slack.db/team-context team-id)
                                  {:redirect (str (:uri req) "?" (:query-string req))}))
          {:status 200
           :headers {"Content-Type" "text/html"}
           :body (let [domain (:slack/team-domain (slack.db/team-context team-id))]
                   (str "You need an invite link to join <a href='https://" domain ".slack.com'>" domain ".slack.com</a>. "
                        "Please contact an organizer."))})))))

(defn find-or-create-channel [{:sparkboard/keys [board-id
                                                 account-id
                                                 project-id
                                                 project-title]}]
  (or (slack.db/project->linked-channel project-id)
      (let [{:keys [slack/team-id slack/team-name]} (slack.db/board->team board-id)
            {:keys [slack/user-id]} (slack.db/account->team-user {:slack/team-id team-id
                                                                  :sparkboard/account-id account-id})
            project-title (v/format-channel-name (str "project-" project-title))]
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
                              user-id]} (slack.db/slack-context team-id user-id)
                domain (slack.db/board-domain board-id)
                project-url (str (urls/sparkboard-host domain) "/project/" project-id)
                channel-id (-> (spy-args (slack.api/request! "conversations.create"
                                                             {:auth/token bot-token}
                                                             {:user_ids [bot-user-id] ;; adding user-id here does not work
                                                              :is_private false
                                                              :name project-title}))
                               :channel
                               :id)]
            (spy-args (slack.api/request! "conversations.invite"
                                          {:auth/token bot-token}
                                          {:channel channel-id
                                           :users [user-id]}))
            (spy-args (slack.api/request! "conversations.setTopic"
                                          {:auth/token bot-token}
                                          {:channel channel-id
                                           :topic (v/link "View on Sparkboard" project-url)}))
            (spy-args (slack.db/link-channel-to-project! {:slack/team-id team-id
                                                          :slack/channel-id channel-id
                                                          :sparkboard/project-id project-id}))
            (assoc context :slack/channel-id channel-id))))))

(defn project-channel-deep-link [{:keys [slack/team-id
                                         slack/channel-id]}]
  (urls/app-redirect {:app (-> env/config :slack :app-id)
                      :domain (:slack/team-domain (slack.db/team-context team-id))
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

(defn missing-handler [{:keys [:slack/handler-id]}]
  ;; block_action events are sent even for things like buttons with URLs.
  ;; set an action-id of :no-op for these to avoid seeing a warning/error.
  (when (not= "no-op" (namespace handler-id))
    (log/error :unhandled-request handler-id)))


(defn parse-slack-params [{:as params :keys [event payload challenge]}]
  (cond challenge
        {:slack/challenge challenge}
        event
        #:slack{:event event
                :user-id (if (map? (:user event)) ;; sometimes `user` is an id string, sometimes a map containing the id
                           (:id (:user event))
                           (:user event))
                :team-id (:team_id params)
                :handler-id (keyword "event" (:type event))}

        payload
        (let [payload (-> (json->clj payload)
                          ;; private_metadata is deserialized here, and serialized in `slack.hiccup`
                          (update-in [:view :private_metadata] #(if (str/blank? %) nil (transit/read %))))]
          #:slack{:user-id (-> payload :user :id)
                  :team-id (-> payload :team :id)
                  :payload payload
                  :handler-id (case (:type payload)
                                "shortcut" (keyword "shortcut" (:callback_id payload))
                                "view_submission" (keyword (-> payload :view :callback_id) "submit")
                                "view_closed" (keyword (-> payload :view :callback_id) "close")
                                "block_actions" (keyword (-> payload :actions first :action_id))
                                (keyword "UNKNOWN_PAYLOAD_TYPE" (:type payload "nil")))})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Async processing (mostly parallel Slack HTTP responses)

(defn goetz
  "Number of threads to have in a thread pool, per Brian Goetz's 'Java Concurrency in Practice' formula'.
  `wait-time`    = approximate total time (ms) waiting (e.g. on Firebase or Slack requests)
  `service-time` = approximate time (ms) processing requests"
  [wait-time service-time]
  (int (* (.availableProcessors (Runtime/getRuntime))
          (+ 1 (/ wait-time service-time)))))

(defonce pool
  (java.util.concurrent.Executors/newFixedThreadPool (goetz 500 10)))

(comment
 (.submit ^java.util.concurrent.ExecutorService pool
          ^Callable #(log/info (inc 5))))

;; Declare JVM-wide uncaught exception handler, so exceptions on the
;; thread pool are logged as errors instead of merely printed.
;; from https://stuartsierra.com/2015/05/27/clojure-uncaught-exceptions
(Thread/setDefaultUncaughtExceptionHandler
 (reify Thread$UncaughtExceptionHandler
   (uncaughtException [_ thread ex]
     (log/error (str "Uncaught exception on" (.getName thread)) ex))))

;;;;;;;;;;;;;;;;;;;;;;;;




(defn handle-slack-req [req]
  (let [{:as context
         :slack/keys [challenge team-id user-id handler-id]} (parse-slack-params (:params req))]
    (log/trace "[slack-req]" req)
    (log/trace "[slack-context]" handler-id context)
    (if challenge
      (ring.http/ok challenge)
      (let [context (merge context (slack.db/slack-context team-id user-id))
            handler (@v/registry handler-id missing-handler)
            exec #(binding [slack.api/*context* context] (handler context))]
        (if (:response-action? (meta handler)) ;; add ^:response-action? metadata to handlers that may return a response
          (or (exec) (ring.http/ok))
          (do (.execute ^java.util.concurrent.ExecutorService pool ^Callable exec)
              (ring.http/ok)))))))

(def routes
  ["/" (merge {"slack-api" handle-slack-req
               "slack-api/oauth-redirect" slack-oauth/redirect
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
                                              :domain (:slack/team-domain (slack.db/team-context team))))))
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

(def handlers*
  (-> (bidi.ring/make-handler routes)
      #_(sb.server.core/wrap-debug-request :slack/last)
      ;; Note: if the following `wrap-restful-format` middleware runs, it will
      ;; stomp on muuntaja's `body-params` elsewhere in the non-slack middleware
      ;; stack. This is why we have `handler` protecting `handler*` from
      ;; executing unless needed.
      (ring.middleware.format/wrap-restful-format {:formats [:json-kw :transit-json]})
      #_(sb.server.core/wrap-debug-request :slack/one)
      wrap-slack-verify
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)))

(defn handlers
  "Handler wrapping all Slack handlers.
  Prevents Slack-specific middleware from affecting non-Slack requests
  by returning `nil` as soon as we know the request does not match a
  Slack route. See comment for `wrap-restful-format` above."
  [req]
  (when (bidi.bidi/match-route routes (:uri req))
    (handlers* req)))
