(ns sb.app.chat.data
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [sb.app.membership.data :as m.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [s-]]
            [sb.server.datalevin :as dl]
            [sb.util :as u]))

;; TODO
;; fix these queries to reflect the change in schema,
;; chat participants are "board memberships" now rather than accounts

(def entity-fields [:entity/id :entity/created-at :entity/updated-at])

(sch/register!
  {:chat/participants    (merge (sch/ref :many)
                                {:doc "Set of participants in a chat."}),
   :chat/key             (merge {:doc "chat/entity + chat/members ids (sorted & joined with +)"}
                                sch/unique-id-str)
   :chat/entity          (merge {:doc "Entity that this chat is about."}
                                (sch/ref :one))
   :chat.message/content {s-           :prose/as-map
                          :db/fulltext true}
   :chat/messages        (merge {:doc "List of messages in a chat."}
                                (sch/ref :many :chat.message/as-map)
                                sch/component)
   :chat/last-message    (merge (sch/ref :one))
   :chat/read-last       {:doc "Map of {account-id, message-id} for the last message read by each participant."
                          s-   [:map-of :uuid :uuid]}
   :chat/as-map          {s- [:map {:closed true}
                              :entity/id
                              :entity/created-at
                              :entity/updated-at
                              :chat/key
                              :chat/entity
                              :chat/participants
                              :chat/messages
                              :chat/read-last]},
   :chat.message/as-map  {s- [:map {:closed true}
                              :entity/id
                              :entity/created-by
                              :entity/created-at
                              :chat.message/content]}})

(defn make-key [& participant-ids]
  (str/join "+" (sort (map sch/unwrap-id participant-ids))))

(q/defx new-message!
  "Create a new chat message."
  {:prepare [az/with-account-id!]}
  [{:as params
    :keys [membership-id
           other-id
           chat-id]} message-content]
  ;; 1. check if chat entity exists
  ;; 2. add chat message to chat entity, creating one if it doesn't exist
  (let [existing-chat (if chat-id
                        (db/entity chat-id)
                        ;; TODO handle new chat creation
                        (comment
                          (dl/q '[:find (pull ?e [* {:chat/participants [:entity/id]}
                                                  {:chat/entity [:entity/id]}]) .
                                  :in $ ?account-id ?other-id ?entity-id
                                  :where
                                  [?e :chat/participants ?account-id]
                                  [?e :chat/participants ?other-id]
                                  [?e :chat/entity ?entity-id]]
                                account-id
                                other-id
                                entity-id)
                          (db/entity [:chat/key (make-key account-id other-id)])))
        new-message   (-> (dl/new-entity {:chat.message/content message-content}
                                         :chat.message
                                         :by membership-id)
                          (assoc :db/id "new-message"))
        new-chat      (or existing-chat
                          (dl/new-entity {:db/id             "new-chat"
                                          :chat/participants [membership-id other-id]
                                          :chat/entity       (-> (db/entity membership-id)
                                                                 :membership/entity
                                                                 :db/id)
                                          :chat/key          (make-key membership-id other-id)
                                          :chat/messages     [new-message]}
                                         :chat))
        tx-report     (db/transact! (if existing-chat
                                      [[:db/add (:db/id existing-chat) :chat/messages "new-message"]
                                       new-message]
                                      [new-chat]))]
    {:chat-id    (sch/wrap-id (:entity/id new-chat))
     :message-id (sch/wrap-id (:entity/id new-message))}))

(def chat-fields-meta
  [:entity/id
   :entity/updated-at
   :chat/read-last
   {:chat/entity [:entity/id
                  :entity/title
                  {:image/avatar [:entity/id]}]}
   {:chat/participants [{:membership/member [:entity/id
                                             :entity/kind
                                             :account/display-name
                                             {:image/avatar [:entity/id]}]}
                        {:membership/entity [:entity/id]}
                        :entity/kind
                        :entity/id]}])

(def message-fields
  [:entity/id
   :entity/created-at
   {:entity/created-by [:entity/id]}
   {:chat.message/content [:prose/format
                           :prose/string]}])

(defn ensure-participant!
  "Throw if account-id is not a participant in chat."
  [account-id chat]
  #?(:clj
     (do (when-not (contains? (into #{}
                                    (map :db/id)
                                    (:chat/participants chat))
                              (:db/id (m.data/membership account-id (:chat/entity chat))))
           (az/unauthorized! "You are not a participant in this chat."))
         chat)))

(def chat-fields-full
  (conj chat-fields-meta {:chat/messages message-fields}))

(q/defquery chat
  "Get a chat by chat-id"
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id chat-id]}]
  (ensure-participant! account-id (dl/entity chat-id))
  (q/pull chat-fields-full chat-id))

(q/defquery chats-list
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id]}]
  (->> (db/entity account-id)
       :membership/_member
       (mapcat :chat/_participants)
       (sort-by :entity/updated-at u/compare:desc)
       (mapv (fn [e]
               (-> (q/pull chat-fields-meta e)
                   (assoc :chat/last-message
                          (q/pull message-fields
                                  (last (:chat/messages e)))))))))

(defn read? [{:keys [account-id]} {:chat/keys [entity last-message messages read-last]}]
  (let [membership-id   (:entity/id (m.data/membership account-id entity))
        last-message-id (:entity/id (or last-message (last messages)))]
    (or (= (get read-last membership-id) last-message-id)
        (= membership-id (:entity/id (:entity/created-by last-message))))))

(def unread? (complement read?))

(q/defquery chat-counts
  {:prepare [az/with-account-id!]}
  [params]
  (let [chats @(q/$ chats-list params)]
    {:total  (count chats)
     :unread (->> chats
                  (filter (partial unread? params))
                  count)}))

(q/defx mark-read!
  "Mark messages as read by account-id."
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id chat-id message-id]}]
  (let [chat (dl/entity chat-id)
        _ (ensure-participant! account-id chat)
        membership (m.data/membership account-id (:chat/entity chat))]
    (db/transact! [[:db/add chat-id
                    :chat/read-last (merge (:chat/read-last chat)
                                           {(:entity/id membership) (sch/unwrap-id message-id)})]]))
  nil)