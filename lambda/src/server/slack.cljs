(ns server.slack
  "Slack API"
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [kitchen-async.promise :as p]
            [lambdaisland.uri :as uri]
            [org.sparkboard.firebase-tokens :as tokens]
            [org.sparkboard.http :as http]
            [org.sparkboard.slack.linking :as slack-db]
            [server.blocks :as blocks]
            [server.common :as common]
            [server.deferred-tasks :as tasks]))

(def slack-config (:slack common/config))

(defn from-slack? [event]                                   ;; FIXME ran into trouble with
  ;; goog.crypt.Sha256 so using a hack for
  ;; now. TODO implement HMAC check per
  ;; https://api.slack.com/authentication/verifying-requests-from-slack
  (= (j/get-in event [:headers :user-agent])
     "Slackbot 1.0 (+https://api.slack.com/robots)")
  ;; TODO
  ;; 1. Check X-Slack-Signature HTTP header
  #_(let [s (string/join ":" [(j/get evt :version)
                              (j/get-in evt [:headers :x-slack-request-timestamp])
                              (j/get evt :body)])]))

(def base-uri "https://slack.com/api/")

(def scopes ["channels:read"
             "chat:write"
             "commands"
             "groups:read"
             "users:read"
             "users:read.email"])

;; TODO refactir some of content-type & headers into a `def`


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Direct HTTP calls
(defn get+ [family-method {:keys [query token]}]
  (http/fetch-json+ (str base-uri family-method
                         (when query
                           (str "?" (uri/map->query-string query))))
                    (j/lit {:method "GET"
                            :Content-Type "application/json; charset=utf-8"
                            :headers {:Authorization (str "Bearer " token)}})))

;; XXX may or may not work for the rest of the API; breaks on chat.PostMessage for unknown reasons I suspect are related to node-fetch possibly mixing URL parameters with a JSON body, which makes Slack choke with "channel_not_found"
(defn post+ [family-method {:keys [body query token]}]
  (http/fetch-json+ (str base-uri family-method (when query
                                                  (str "?" (uri/map->query-string query))))
                    #js{:method "post"
                        :Content-Type "application/json; charset=utf-8"
                        :headers #js{:Authorization (str "Bearer " token)}
                        :body (clj->js body) #_(.stringify js/JSON (clj->js body))}))

(tasks/register-handler! `post+)

(comment
  (p/-> (get+ "users.list")
        println))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Convenience wrappers over individual endpoints
(defn views-open! [token trigger-id blocks]
  #_ (println "[views-open!] JSON blocks:" (blocks/to-json blocks))
  (p/->> (post+ "views.open"
                {:query {:trigger_id trigger-id
                         :view (blocks/to-json blocks)}
                 :token token})
         (http/assert-ok)
         ;; TODO better callback
         (println "slack views.open response:")))

(tasks/register-handler! `views-open!)

(defn views-update! [token view-id blocks]
  (p/->> (post+ "views.update"
                {:query {:view_id view-id
                         :view (blocks/to-json blocks)}
                 :token token})
         ;; TODO better callback
         (println "slack views.update response:")))

(tasks/register-handler! `views-update!)

(defn slack-user-by-email
  "Returns slack user id for the given email address, if found"
  [email]
  (p/let [res (get+ "users.lookupByEmail" {:query {:email email}})]
    ;; UNCLEAR: how does Slack know what workspace we are interested in?
    (when (j/get res :ok)
      ;; user contains team_id, id, is_admin
      (j/get res :user))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Oauth flow

(j/defn oauth-install-redirect
  "Users navigate to /slack/install to add this app to a Slack team.

   They are sent to this URL from Sparkboard, which adds a `state`
   query parameter - a signed token with a payload containing the user's
   account-id and board-id from Sparkboard.

   This is how we verify who the user is, and what board to connect the
   app to (a board for which the user must be an admin)"
  [^:js {:as req :keys [query]} ^js res next]
  (p/let [{:keys [team-id board-id]} (tokens/decode (:state query))
          error (when board-id
                  (p/let [entry (slack-db/board->team board-id)]
                    (when (and entry (not= board-id (:board-id entry)))
                      (str "This board is already linked to the Slack team " (:team-name entry)))))]
    (if error
      (-> res (.status 400) (.send error))
      (.redirect res
                 (str "https://slack.com/oauth/v2/authorize?"
                      (uri/map->query-string
                        {:scope (str/join "," scopes)
                         :team team-id
                         :client_id (:client-id slack-config)
                         :redirect_uri (str (common/lambda-root-url req) "/slack/oauth-redirect")
                         ;; the `state` query parameter - a signed token from Sparkboard
                         :state (:state query)}))))))

(j/defn oauth-redirect [^:js {:as req :keys [body query]} res next]
  (let [{:keys [code state]} query
        {:keys [board-id account-id only-install]} (tokens/decode state)]
    (assert (or (and board-id account-id)
                only-install) "token must include board-id and account-id")
    (p/let [response (post+ "oauth.v2.access"
                            {:query {:code code
                                     :client_id (:client-id slack-config)
                                     :client_secret (:client-secret slack-config)
                                     :redirect_uri (str (common/lambda-root-url req) "/slack/oauth-redirect")}})]
      (j/let [^:js {app-id :app_id
                    :keys [bot_user_id
                           access_token]
                    {team-id :id team-name :name} :team
                    {user-id :id} :authed_user} response]
        (p/let [user-response (get+ "users.info" {:query {:user user-id}
                                                  :token access_token})]
          (assert (j/get-in user-response [:user :is_admin])
                  "Only an admin can install the Sparkboard app")
          (p/try
            (when board-id
              (slack-db/link-team-to-board!
                {:slack/team-id team-id
                 :sparkboard/board-id board-id}))
            (p/all
              [(slack-db/install-app!
                 {:slack/team-id team-id
                  :slack/team-name team-name
                  :slack/app-id app-id
                  :slack/bot-token access_token
                  :slack/bot-user-id bot_user_id})
               (when account-id
                 (slack-db/link-user-to-account!
                   {:slack/team-id team-id
                    :slack/user-id user-id
                    :sparkboard/account-id account-id}))])
            (.redirect res (slack-db/deep-link-to-home app-id team-id))
            (p/catch js/Error ^js e
              (.send res 400 (.-message e)))))))))

(defn only-install-link
  [& [{:keys [slack/team-id
              lambda/root]}]]
  ;; link that will let a user install app without linking to a board
  (str root "/slack/install?state=" (tokens/encode
                                      {:only-install true
                                       :team-id team-id})))

(comment
  (only-install-link {:lambda/root "https://slack-matt.ngrok.io"}))
