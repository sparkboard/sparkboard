(ns sparkboard.app.chat
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.authorize :as az]
            [sparkboard.schema :as sch :refer [s-]]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.ui :as ui]
            [sparkboard.util :as u]
            [sparkboard.websockets :as ws]
            [sparkboard.routes :as routes]
            #?(:cljs [yawn.hooks :as h])))

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


(defn make-key [entity-id & participant-ids]
  (str/join "+" (sort (map sch/unwrap-id (cons entity-id participant-ids)))))

(ws/defx db:new-message!
  "Create a new chat message."
  {:prepare [az/with-account-id!]}
  [{:keys [account-id
           other-id
           entity-id]} message-content]
  ;; 1. check if chat entity exists
  ;; 2. add chat message to chat entity, creating one if it doesn't exist
  (let [existing-chat (db/entity [:chat/key (make-key entity-id account-id other-id)])
        new-message   (-> (dl/new-entity {:chat.message/content message-content}
                                         :chat.message
                                         :by account-id)
                          (assoc :db/id "new-message"))
        tx            (if existing-chat
                        [[:db/add (:db/id existing-chat) :chat/messages "new-message"]
                         new-message]
                        [(dl/new-entity {:db/id             "new-chat"
                                         :chat/participants [account-id other-id]
                                         :chat/entity       entity-id
                                         :chat/key          (make-key entity-id account-id other-id)
                                         :chat/messages     [new-message]}
                                        :chat)])
        tx-report     (db/transact! tx)]
    ;; return new chat
    (db/pull '[*] (get-in tx-report [:tempids "new-message"]))))

(defn compare:desc
  "Compare two values in descending order."
  [a b]
  (compare b a))

(def chat-fields-meta
  [:entity/id
   :entity/updated-at
   :chat/read-last
   {:chat/entity [:entity/id
                  :entity/title
                  {:image/avatar [:asset/id]}]}
   {:chat/participants [:account/display-name
                        {:image/avatar [:asset/id]}
                        :entity/id]}])

(def message-fields
  [:entity/id
   :entity/created-at
   {:entity/created-by [:entity/id]}
   {:chat.message/content [:prose/format
                           :prose/string]}])

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

(def chat-fields-full
  (conj chat-fields-meta {:chat/messages message-fields}))

(ws/defquery db:chat
  "Get a chat by other-id."
  {:prepare [az/with-account-id!]}
  [{:keys [account-id entity-id other-id]}]
  (some->> (db/pull chat-fields-full [:chat/key (make-key entity-id account-id other-id)])
           (ensure-participant! account-id)))

(ws/defquery db:chats
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id entity-id]}]
  (->> (db/where [[:chat/participants account-id]
                  [:chat/entity entity-id]])
       (mapv (fn [e]
               (-> (db/pull chat-fields-meta e)
                   (assoc :chat/last-message
                          (db/pull message-fields
                                   (last (:chat/messages e)))))))))

(defn unread? [account-id {:keys [chat/last-message]}]
  (let [account-id (sch/unwrap-id account-id)]
    (or (= (get (:chat.message/read-by last-message) account-id)
           (:entity/id last-message))
        (= account-id (:entity/id (:entity/created-by last-message))))))

(ui/defview sidebar [{:as params :keys [account-id entity-id]}]
  [:div.flex.flex-col.text-sm.divide-y.px-1.py-1
   (doall
     (for [{:as        chat
            :chat/keys [participants
                        last-message]} (db:chats {:entity-id entity-id})
           :let [other-participants
                          (remove #(sch/id= account-id %) participants)
                 current? (sch/id= (:other-id params) (first other-participants))]]
       [:a.flex.flex-col.py-2.cursor-default.mx-1.px-1
        {:href  (routes/path-for `chat (assoc params :other-id
                                                     (:entity/id (first other-participants)))) ;; TODO path to chat
         :key   (:entity/id chat)
         :class (when current? "bg-blue-100 rounded")}
        (str "chat: " (:entity/id chat))
        [:div.flex.items-center.gap-2
         [:div.w-2.h-2.rounded-full
          {:class (if (and (unread? account-id chat) #_(not current?)) "bg-blue-500")}]
         ;; participants
         (for [other other-participants]
           [:div.font-bold {:key (:entity/id other)} (:account/display-name other)])]
        [:div.pl-4.line-clamp-2
         (ui/show-prose
           (:chat.message/content last-message))]]))])

(ui/defview with-sidebar [params child]
  [:div.flex.divide-x.h-screen
   [:di {:class ["min-w-48 max-w-md w-1/3"]}
    [sidebar params]]
   [:div.flex-auto child]])

#?(:cljs
   (defn effect! [f & args]
     (routes/POST "/effect" (into [f] args))))

(ui/defview chat-message [{:keys [account-id]} {:keys [chat.message/content
                                                       entity/created-by
                                                       entity/id]}]
  [:div.p-2.flex.flex-col
   {:class ["max-w-[600px] rounded-[12px]"
            (if (= (sch/unwrap-id account-id)
                   (sch/unwrap-id created-by))
              "bg-blue-500 text-white place-self-end"
              "bg-gray-100 text-gray-900")]
    :key   id}
   (ui/show-prose content)])

(ui/defview chat-detail [params]
  (let [!message           (h/use-state nil)
        !response          (h/use-state nil)
        !scrollable-window (h/use-ref)
        message            (u/guard @!message (complement str/blank?))
        {:as chat :chat/keys [messages]} (db:chat params)
        keydown-handler    (fn [e]
                             (when ((ui/keydown-handler
                                      {:Enter
                                       (fn []
                                         (reset! !response {:pending true})
                                         (p/let [response (effect! `db:new-message!
                                                                   params
                                                                   {:prose/format :prose.format/markdown
                                                                    :prose/string message})]
                                           ;; TODO
                                           ;; ensure that `effect!` returns an {:error ..} map
                                           (prn response)
                                           (j/log response)
                                           (reset! !response response)
                                           (when-not (:error response)
                                             (reset! !message nil))))}) e)
                               (.preventDefault e)))]
    (h/use-effect
      (fn []
        (when-let [el @!scrollable-window]
          (set! (.-scrollTop el) (.-scrollHeight el))))
      [(count messages) @!scrollable-window])
    [:<>
     #_[:pre (ui/pprinted chat)]
     [:div.flex-auto.overflow-y-scroll.flex.flex-col.gap-3
      {:ref !scrollable-window}
      (doall (map (partial chat-message params) messages))]
     [ui/auto-height-textarea
      {:class       ["rounded-lg m-1 p-2 whitespace-pre-wrap text-sm min-h-[38px] bg-gray-50 border-gray-300 flex-none"
                     "disabled:bg-gray-200 disabled:text-gray-500"]
       :type        "text"
       :placeholder "Aa"
       :disabled    (:pending @!response)
       ;:style       {:max-height 200}
       :on-key-down keydown-handler
       :on-change   #(reset! !message (j/get-in % [:target :value]))
       :value       (or message "")}]]))

(ui/defview chats
  {:route ["/chats/" ['entity/id :entity-id]]}
  [{:as params :keys [other-id]}]
  [with-sidebar params
   [:div.flex.flex-col.items-stretch.h-full.w-full.py-2.relative
    (when other-id
      [chat-detail params])]])

(routes/register-route chat
  {:alias-of chats
   :route    ["/chats/" ['entity/id :entity-id] "/" ['entity/id :other-id]]})

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

  (def new-chat (db:new-message! {:account-id (:entity/id m-account)
                                  :other-id   (:entity/id m-other)
                                  :entity-id  (:entity/id m-entity)}))
  (db/where [[:chat/key (:chat/key new-chat)]])

  )