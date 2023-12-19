(ns sb.app.chat.data
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [s-]]
            [sb.server.datalevin :as dl]
            [sb.util :as u]))

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

(defn make-key [entity-id & participant-ids]
  (str/join "+" (sort (map sch/unwrap-id (cons entity-id participant-ids)))))

(q/defx new-message!
  "Create a new chat message."
  {:prepare [az/with-account-id!]}
  [{:keys [account-id
           other-id
           chat-id
           entity-id]} message-content]
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
                          (db/entity [:chat/key (make-key entity-id account-id other-id)])))
        new-message   (-> (dl/new-entity {:chat.message/content message-content}
                                         :chat.message
                                         :by account-id)
                          (assoc :db/id "new-message"))
        new-chat      (or existing-chat
                          (dl/new-entity {:db/id             "new-chat"
                                          :chat/participants [account-id other-id]
                                          :chat/entity       entity-id
                                          :chat/key          (make-key entity-id account-id other-id)
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
   {:chat/participants [:account/display-name
                        {:image/avatar [:entity/id]}
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
     (do
       (when-not (contains?
                   (into #{}
                         (map :entity/id)
                         (:chat/participants chat))
                   (sch/unwrap-id account-id))
         (az/unauthorized! "You are not a participant in this chat."))
       chat)))

(def chat-fields-full
  (conj chat-fields-meta {:chat/messages message-fields}))

(q/defquery chat
  "Get a chat by chat-id"
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id chat-id]}]
  (some->> (q/pull chat-fields-full chat-id)
           (ensure-participant! account-id)))

(q/defquery chats-list
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id]}]
  (->> (db/entity account-id)
       :chat/_participants
       (sort-by :entity/updated-at u/compare:desc)
       (mapv (fn [e]
               (-> (q/pull chat-fields-meta e)
                   (assoc :chat/last-message
                          (q/pull message-fields
                                  (last (:chat/messages e)))))))))

(defn read? [account-id {:chat/keys [last-message messages read-last]}]
  (let [last-message-id (:entity/id (or last-message (last messages)))
        account-id      (sch/unwrap-id account-id)]
    (or (= (get read-last account-id) last-message-id)
        (= account-id (:entity/id (:entity/created-by last-message))))))

(def unread? (complement read?))

(q/defquery chat-counts
  {:prepare [az/with-account-id!]}
  [params]
  (let [chats @(q/$ chats-list params)]
    {:total  (count chats)
     :unread (->> chats
                  (filter (partial unread? (:account-id params)))
                  count)}))

(q/defx mark-read!
  "Mark messages as read by account-id."
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id chat-id message-id]}]
  (ensure-participant! account-id (dl/entity chat-id))
  (db/transact! [[:db/add chat-id
                  :chat/read-last (merge (:chat/read-last (db/entity chat-id))
                                         {(sch/unwrap-id account-id) (sch/unwrap-id message-id)})]])
  nil)