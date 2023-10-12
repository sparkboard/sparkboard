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
                              :chat.message/read-by
                              :chat.message/content]}})

#?(:clj
   (defn make-key [entity-id participant-ids]
     (str/join "+" (map sch/unwrap-id (cons entity-id participant-ids)))))

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
   (defn db:chats
     "Get all chats that account-id is a participant in for the given entity."
     {:endpoint {:query true}
      :prepare  [az/with-account-id!]}
     [{:keys [entity-id account-id]}]
     (->> (db/where [[:chat/participants account-id]
                     [:chat/entity entity-id]])
          (map (db/pull [:entity/id
                         :entity/updated-at
                         :chat/read-last
                         {:chat/entity [:entity/id]}
                         {:chat/participants [:account/display-name
                                              :image/avatar
                                              :entity/id]}
                         {:chat/messages [:entity/created-at
                                          {:chat.message/content [:prose/format
                                                                  :prose/string]}
                                          {:entity/created-by [:entity/id]}]}]))
          (sort-by :entity/updated-at compare:desc)))

   )

#?(:clj
   (defn ensure-participant!
     "Throw if account-id is not a participant in chat."
     [account-id chat]
     (when-not (contains?
                 (into #{}
                       (map :entity/id)
                       (:chat/participants chat))
                 (sch/unwrap-id account-id))
       (az/unauthorized! "You are not a participant in this chat."))
     chat))

(ws/defx db:mark-read!
  "Mark messages as read by account-id."
  {:prepare [az/with-account-id!]}
  [{:keys [account-id message-id]}]
  (ensure-participant! account-id (dl/entity message-id))
  (db/transact! [[:db/add message-id :chat.message/read-by account-id]])
  true)

(def chat-fields-meta
  [:entity/id
   :entity/updated-at
   {:chat/entity [:entity/id
                  :entity/title
                  {:image/avatar [:asset/id]}]}
   {:chat/participants [:account/display-name
                        {:image/avatar [:asset/id]}
                        :entity/id]}])

(def message-fields
  [:entity/created-at
   {:entity/created-by [:entity/id]}
   {:chat.message/content [:prose/format
                           :prose/string]}])

(def chat-fields-full
  (conj chat-fields-meta {:chat/messages message-fields}))

(ws/defquery db:chat
  "Get a chat by id."
  {:prepare [az/with-account-id!]}
  [{:keys [account-id chat-id]}]
  (->> (db/pull chat-fields-full chat-id)
       (ensure-participant! account-id)))

(ws/defquery db:chats
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id entity-id]}]
  (prn params)
  (->> (db/where [[:chat/participants account-id]
                  [:chat/entity entity-id]])
       (mapv (fn [e]
               (-> (db/pull chat-fields-meta e)
                   (assoc :chat/last-message
                          (db/pull message-fields
                                   (last (:chat/messages e)))))))))

(ui/defview chat
  {:route ["/chats/"
           ['entity/id :entity-id]
           "/"
           ['entity/id :participant-id]]}
  [{:keys [account-id
           entity-id
           participant-id]}]
  [:div
   "single chat"
   ]
  ;; pick entity
  ;; pick another member of that entity
  ;; show chat window
  ;; add chat message (create chat if it does not exist)
  ;; mark chats as read
  )

(ui/defview chats
  {:route ["/chats/" ['entity/id :entity-id]]}
  [{:keys [account-id entity-id]}]
  [:div
   [:pre (ui/pprinted (db:chats {:entity-id entity-id}))]

   ;; TODO
   ;; show unread status
   ;; show list in sidebar
   ;; click chat to view
   ;; -> in another route

   (doall
     (for [{:keys [chat/key
                   chat/participants
                   chat/last-message]} (db:chats {:entity-id entity-id})
           :let [other-participants
                 (remove #(= (sch/unwrap-id account-id)
                             (:entity/id %)) participants)]]
       [:div {:key key}
        (for [{:keys [entity/id
                      account/display-name
                      image/avatar]} other-participants
              :let [avatar-src (some-> avatar (ui/asset-src :avatar))]]
          [:div {:key id}
           (when avatar-src
             [:img {:src avatar-src}])
           display-name])
        (ui/show-prose
          (:chat.message/content last-message))]))])

(comment

  (def entity-id
    (->> (db/where [[:entity/title "écoHack Montréal"]])
         first
         :db/id))

  (def account-id
    (->> (db/entity [:account/email "mhuebert@gmail.com"])
         :db/id))

  (db:chats {:entity-id entity-id :account-id account-id})


  (db:chats {:entity-id  board-id
             :account-id account-id})

  (def m-account (-> m-chat :chat/participants first))
  (def m-other (-> m-chat :chat/participants second))
  (def m-entity (->> (db/where [:project/sticky?]) first))

  (def new-chat (db:new-chat! {:account-id (:entity/id m-account)
                               :other-id   (:entity/id m-other)
                               :entity-id  (:entity/id m-entity)}))
  (db/where [[:chat/key (:chat/key new-chat)]])

  )