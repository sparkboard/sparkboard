(ns org.sparkboard.slack.requests
  (:require [tools.sparkboard.slack.api :refer [*context* request!]]))

(def channel-info
  (memoize
    (fn channel-info
      ([channel-id] (channel-info (:slack/bot-token *context*) channel-id))
      ([token channel-id]
       (-> (request! "conversations.info" {:auth/token token} {:channel channel-id})
           :channel)))))

(def team-info
  (memoize
    (fn team-info
      ([team-id] (team-info (:slack/bot-token *context*) team-id))
      ([token team-id]
       (-> (request! "team.info" {:auth/token token} {:team team-id})
           :team)))))

(defn user-info [token user-id]
  (:user (request! "users.info" {:auth/token token} {:user user-id})))

(defn message-user! [context blocks]
  (request! "chat.postMessage"
            {:auth/token (:slack/bot-token context)}
            {:channel (:slack/user-id context)
            :blocks blocks}))

