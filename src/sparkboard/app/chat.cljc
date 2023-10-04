(ns sparkboard.app.chat
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [sparkboard.authorize :as az]
            [sparkboard.schema :as sch :refer [s-]]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.ui :as ui]
            [sparkboard.websockets :as ws]))

(sch/register!
  {:chat/participants    (merge (sch/ref :many)
                                {:doc "Set of participants in a chat."}),
   :chat/key             (merge {:doc "chat/entity + chat/members ids (sorted & joined with +)"}
                                sch/unique-id-str)
   :chat/entity          (merge {:doc "Entity that this chat is about."}
                                (sch/ref :one))
   :chat.message/content {s-           :prose/as-map
                          :db/fulltext true}
   :chat/messages        (merge (sch/ref :many :chat.message/as-map)
                                {:doc "List of messages in a chat."}),
   :chat/read-by         (merge
                           (sch/ref :many)
                           {:doc  "Set of members who have read the most recent message.",
                            :todo "Map of {member, last-read-message} so that we can show unread messages for each member."})
   :chat/as-map          {s- [:map {:closed true}
                              :entity/id
                              :entity/created-at
                              :entity/updated-at
                              :chat/key
                              :chat/entity
                              :chat/participants
                              :chat/messages]},
   :chat.message/as-map  {s- [:map {:closed true}
                              :entity/id
                              :entity/created-by
                              :entity/created-at
                              :chat.message/read-by
                              :chat.message/content]}})

#?(:clj
   (defn make-key [entity-id participant-ids]
     (str/join "+" (map dl/unwrap-id (cons entity-id participant-ids)))))

#?(:clj
   (defn db:new-chat!
     "Create a new chat. Returns the chat entity."
     {:endpoint {:query true}
      :prepare  [az/with-account-id!]}
     [{:keys [account-id
              other-id
              entity-id]}]
     (-> {:chat/participants [account-id other-id]
          :chat/entity       entity-id
          :chat/key          (make-key entity-id [account-id other-id])}
         (dl/new-entity :chat
                        :by account-id)
         (doto (-> vector db/transact!)))))

(defn compare:desc
  "Compare two values in descending order."
  [a b]
  (compare b a))

#?(:clj
   (defn db:my-chats
     "Get all chats that account-id is a participant in for the given entity."
     {:endpoint {:query true}
      :prepare  [az/with-account-id!]}
     [{:keys [entity-id account-id]}]
     (->> (db/where [[:chat/participants account-id]
                     [:chat/entity entity-id]])
          ;; impedance mismatch: always needing to wrap [:entity/id _],
          ;; yet not supposed to expose internal ids. makes for weird edges.
          (map (db/pull [:entity/id
                         :entity/updated-at
                         {:chat/entity [:entity/id]}
                         {:chat/participants [:account/display-name
                                              :image/avatar
                                              :entity/id]}
                         {:chat/messages [:entity/created-at
                                          {:entity/created-by [:entity/id]}
                                          {:chat.message/read-by [:entity/id]}]}]))
          (sort-by :entity/updated-at compare:desc))))

#?(:clj
   (defn ensure-participant!
     "Throw if account-id is not a participant in chat."
     [account-id chat]
     (when-not (contains?
                 (into #{}
                       (map :entity/id)
                       (:chat/participants chat))
                 (dl/unwrap-id account-id))
       (az/unauthorized! "You are not a participant in this chat."))
     chat))

(ws/defx db:mark-read!
  "Mark messages as read by account-id."
  {:prepare [az/with-account-id!]}
  [{:keys [account-id message-id]}]
  (ensure-participant! account-id (dl/entity message-id))
  (db/transact! [[:db/add message-id :chat.message/read-by account-id]])
  true)

(ws/defquery db:chat
  "Get a chat by id."
  {:prepare [az/with-account-id!]}
  [{:keys [account-id chat-id]}]
  (->> (db/pull [:entity/id
                 :entity/updated-at
                 {:chat/entity [:entity/id
                                :entity/title
                                {:image/avatar [:asset/id]}]}
                 {:chat/participants [:account/display-name
                                      :image/avatar
                                      :entity/id]}
                 {:chat/messages [:entity/created-at
                                  {:entity/created-by [:entity/id]}
                                  {:chat.message/content [:prose/format
                                                          :prose/string]}
                                  {:chat.message/read-by [:entity/id]}]}]
                chat-id)
       (ensure-participant! account-id)))

(ui/defview chat
  {:route "/chat"}
  [_]
  [:div
   "Hello there"
   [:a {:href "/foof"} "Foof"]]
  ;; pick entity
  ;; pick another member of that entity
  ;; show chat window
  ;; add chat message (create chat if it does not exist)
  ;; mark chats as read
  )

(comment
  (def m-chat (->> (db/where [:chat/messages])
                   (filter #(> (count (:chat/messages %)) 3))
                   (drop 20)
                   first
                   ))
  (def m-account (-> m-chat :chat/participants first))
  (def m-other (-> m-chat :chat/participants second))
  (def m-entity (->> (db/where [:project/sticky?]) first))

  (def new-chat (db:new-chat! {:account-id (:entity/id m-account)
                               :other-id   (:entity/id m-other)
                               :entity-id  (:entity/id m-entity)}))
  (db/where [[:chat/key (:chat/key new-chat)]])

  )