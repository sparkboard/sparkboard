(ns sb.app.chat.data
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [s- ?]]
            [sb.server.datalevin :as dl]
            [sb.validate :as validate]
            [sb.util :as u]))

(sch/register!
  {:chat.message/content {s-           :prose/as-map
                          :db/fulltext true}
   :chat.message/recipient (sch/ref :one)
   :chat.message/as-map  {s- [:map {:closed true}
                              :entity/id
                              :entity/kind
                              :entity/created-by
                              :entity/created-at
                              :chat.message/content
                              :chat.message/recipient
                              (? :notification/unread-by)
                              (? :notification/email-to)]}})

(q/defx new-message!
  "Create a new chat message."
  {:prepare [az/with-account-id!]}
  [{:as params
    :keys [account-id
           other-id]}
   message-content]
  (let [message (-> (dl/new-entity {:chat.message/recipient other-id
                                    :notification/unread-by [other-id]
                                    :notification/email-to [other-id]
                                    :chat.message/content message-content}
                                   :chat.message :by account-id)
                    (validate/assert :chat.message/as-map))]
    (db/transact! [message])
    {:entity/id (:entity/id message)}))

(def message-fields
  `[:entity/id
    :entity/kind
    {:entity/created-by ~entity.data/listing-fields}
    {:chat.message/recipient ~entity.data/listing-fields}
    {:notification/unread-by [:entity/id]}
    :chat.message/content])

(q/defquery chat
  "Get a chat by other participant"
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id other-id]}]
  (-> []
      (into (db/where [[:chat.message/recipient other-id]
                       [:entity/created-by account-id]]))
      (into (db/where [[:chat.message/recipient account-id]
                       [:entity/created-by other-id]]))
      (->> (sort-by :entity/created-at)
           (mapv (db/pull message-fields)))
      not-empty))

(def newest-entity (partial max-key #(.getTime (:entity/created-at %))))

(q/defquery chats-list
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id]}]
  (->> (merge-with newest-entity
                   (->> (db/entity (:account-id params))
                        :chat.message/_recipient
                        (u/group-by-with :entity/created-by newest-entity))
                   (->> (db/where [[:entity/created-by account-id]
                                   [:entity/kind :chat.message]])
                        (u/group-by-with :entity/created-by newest-entity)))
       vals
       (sort-by :entity/created-at u/compare:desc)
       (mapv (db/pull message-fields))))

(defn unread-by? [account-id {:notification/keys [unread-by]}]
  (some (partial sch/id= account-id) unread-by))

(q/defquery chat-counts
  {:prepare [az/with-account-id!]}
  [params]
  {:unread
   (->> (db/entity (:account-id params))
        :chat.message/_recipient
        (filter (partial unread-by? (:account-id params)))
        (map :entity/created-by)
        distinct
        count)})

(q/defx mark-read!
  "Mark messages as read by account-id."
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id message-ids]}]
  (db/transact! (mapv #(do [:db/retract % :notification/unread-by account-id])
                      message-ids))
  nil)
