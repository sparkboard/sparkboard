(ns org.sparkboard.slack.oauth
  (:require [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.server.slack.core :as slack :refer [web-api base-uri]]
            [org.sparkboard.firebase.tokens :as tokens]
            [lambdaisland.uri :as uri]
            [clojure.string :as str]
            [org.sparkboard.server.env :as env]
            [ring.util.http-response :as http]
            [org.sparkboard.slack.urls :as urls]
            [org.sparkboard.http :refer [get+ post+]]
            [taoensso.timbre :as log]))

(def slack-config (-> env/config :slack))

(def required-scopes ["channels:join"
                      "channels:manage"
                      "channels:read"
                      "chat:write"
                      "chat:write.public"
                      "commands"
                      "groups:read"
                      "reminders:write"
                      "team:read"
                      "users:read"
                      "users:read.email"])

(defn install-redirect
  "Users navigate to /slack/install to add this app to a Slack team.

   They are sent to this URL from Sparkboard, which adds a `state`
   query parameter - a signed token with a payload containing the user's
   account-id and board-id from Sparkboard.

   This is how we verify who the user is, and what board to connect the
   app to (a board for which the user must be an admin)"
  [{:keys [query-params] :as req}]
  (log/trace :install-redirect/req req)
  (let [{:strs [state]} query-params
        _ (assert state "oauth install requires state param")
        {:as token
         :keys [slack/team-id
                sparkboard/board-id]} (tokens/decode state)
        _ (log/trace :token-claims token)
        error (when board-id
                (when-let [entry (slack-db/board->team board-id)]
                  (when (not= board-id (:sparkboard/board-id entry))
                    (str "This board is already linked to the Slack team " (:slack/team-name entry)))))]
    (if error
      (http/unauthorized error)
      (http/found
        (str "https://slack.com/oauth/v2/authorize?"
             (uri/map->query-string
               {:scope (str/join "," required-scopes)
                :team team-id
                :client_id (:client-id slack-config)
                :redirect_uri (str (:sparkboard/jvm-root env/config) "/slack-api/oauth-redirect")
                ;; the `state` query parameter - a signed token from Sparkboard
                :state state}))))))

(defn res-text [res message]
  (-> res
      (assoc :body message)
      (assoc-in [:headers "Content-Type"] "text/plain")))

(defn redirect
  "This is the main oauth redirect, where Slack sends users who are in the process of installing our Slack app.
   We know who users are already when they land here because we pass Slack a `state` parameter when we start
   the flow which is a signed token containing bits of context."
  [{:keys [query-params]}]
  (let [{:strs [code state]} query-params
        {:as token-claims
         :keys [sparkboard/board-id
                sparkboard/account-id
                slack/team-id
                reinstall?]} (tokens/decode state)
        ;; use the code from Slack to request an access token
        response (get+ (str base-uri "oauth.v2.access")
                       {:query {:code code
                                :client_id (:client-id slack-config)
                                :client_secret (:client-secret slack-config)
                                :redirect_uri (str (:sparkboard/jvm-root env/config) "/slack-api/oauth-redirect")}})
        _ (assert (or (and board-id account-id)
                      team-id
                      reinstall?) "token must include board-id and account-id")]
    (let [{app-id :app_id
           :keys [bot_user_id
                  access_token]
           {team-id :id} :team
           {user-id :id} :authed_user} response
          {:as team-info team-name :name team-domain :domain} (slack/team-info access_token team-id)]
      (log/trace :team-info team-info)
      ;; use the access token to look up the user and make sure they are an admin of the Slack team they're installing
      ;; this app on.
      (let [user-response (get+ (str base-uri "users.info")
                                {:query {:user user-id
                                         :token access_token}})]
        (log/trace :redirect/user-response user-response)

        (assert (get-in user-response [:user :is_admin])
                "Only an admin can install the Sparkboard app")
        (when-let [token-team (:slack/team-id token-claims)]
          (assert (= token-team team-id) "Reinstall must be to the same team"))

        (when board-id
          (log/spy (slack-db/link-team-to-board!
                     {:slack/team-id team-id
                      :sparkboard/board-id board-id})))
        (log/spy (slack-db/install-app!
                   #:slack{:team-id team-id
                           :team-name team-name
                           :team-domain team-domain
                           :app-id app-id
                           :bot-token access_token
                           :bot-user-id bot_user_id}))
        (when account-id
          (log/spy (slack-db/link-user-to-account!
                     {:slack/team-id team-id
                      :slack/user-id user-id
                      :sparkboard/account-id account-id})))
        (http/found (urls/app-redirect {:app app-id :team team-id :domain team-domain}))))))
