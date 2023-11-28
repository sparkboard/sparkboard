(ns sparkboard.app.chat
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.app.member :as member]
            [sparkboard.authorize :as az]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.query :as q]
            [sparkboard.routes :as routes]
            [sparkboard.schema :as sch :refer [s-]]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.ui :as ui]
            [sparkboard.ui.icons :as icons]
            [sparkboard.ui.radix :as radix]
            [sparkboard.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

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


(def search-classes
  (v/classes ["rounded-lg p-2 bg-gray-50 border-gray-300 border"
              "disabled:bg-gray-200 disabled:text-gray-500"]))

(defn make-key [entity-id & participant-ids]
  (str/join "+" (sort (map sch/unwrap-id (cons entity-id participant-ids)))))

(q/defx db:new-message!
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

(q/defquery db:chat
  "Get a chat by chat-id"
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id chat-id]}]
  (some->> (q/pull chat-fields-full chat-id)
           (ensure-participant! account-id)))

(q/defquery db:chats-list
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

(q/defquery db:counts
  {:prepare [az/with-account-id!]}
  [params]
  (let [chats @(q/$ db:chats-list params)]
    {:total  (count chats)
     :unread (->> chats
                  (filter (partial unread? (:account-id params)))
                  count)}))

(ui/defview member-search [params]
  (let [!search-term (h/use-state "")]
    [:<>
     [:div.flex-v.relative.px-1.py-2
      [:input.w-full
       {:class       search-classes
        :value       @!search-term
        :placeholder (tr :tr/find-a-member)
        :on-change   #(reset! !search-term (j/get-in % [:target :value]))}]
      [:div.absolute.right-2.top-0.bottom-0.flex.items-center
       [icons/search "w-5 h-5 absolute right-2"]]]
     (doall
       (for [account (member/db:search {:search-term (h/use-deferred-value @!search-term)})]
         [:div.flex.items-center.gap-2.font-bold
          [ui/avatar {:size 8} account]
          (:account/display-name account)]))]))

(defn other-participant [account-id chat]
  (first (remove #(sch/id= account-id %) (:chat/participants chat))))

(ui/defview chat-snippet
  {:key (fn [_ {:keys [entity/id]}] id)}
  [{:keys [current-chat-id
           account-id]} chat]
  (let [{:as   chat
         :keys [entity/id
                chat/last-message]} chat
        other    (other-participant account-id chat)
        current? (sch/id= current-chat-id id)]
    [:a.flex.gap-2.py-2.cursor-default.mx-1.px-1.cursor-pointer.rounded.items-center.text-sm.w-full.text-left.focus-bg-gray-100
     {:href  (routes/href [`chat {:chat-id id}])
      :class (if current? "bg-blue-100 rounded" "hover:bg-gray-100")}
     [ui/avatar {:size 12 :class "flex-none"} other]
     [:div.flex-v.w-full.overflow-hidden
      [:div.flex.items-center
       [:div.font-bold.flex-auto (:account/display-name other)]
       [:div.w-2.h-2.rounded-full.flex-none
        {:class (when (unread? account-id chat)
                  "bg-blue-500")}]]
      [:div.text-gray-700.hidden.md:line-clamp-2.text-sm
       (ui/show-prose
         (cond-> (:chat.message/content last-message)
                 (sch/id= account-id (:entity/created-by last-message))
                 (update :prose/string (partial str (tr :tr/you) " "))))]]]))

(ui/defview chats-sidebar [{:as             chat
                            :keys           [account-id]
                            current-chat-id :chat-id}]
  [:div.flex-v.px-1.py-2.w-full
   #_[member-search nil]
   (->> (db:chats-list nil)
        (map (partial chat-snippet {:current-chat-id current-chat-id
                                    :account-id      account-id})))])

(ui/defview chat-message
  {:key (fn [_ {:keys [entity/id]}] id)}
  [{:keys [account-id]} {:keys [chat.message/content
                                entity/created-by
                                entity/id]}]
  [:div.p-2.flex-v
   {:class ["max-w-[600px] rounded-[12px]"
            (if (sch/id= account-id created-by)
              "bg-blue-500 text-white place-self-end"
              "bg-gray-100 text-gray-900 place-self-start")]
    :key   id}
   (ui/show-prose content)])

(ui/defview chat-header [{:keys [account-id chat]}]
  (let [close-icon [icons/close "w-6 h-6 hover:opacity-50"]]
    [:div.p-2.text-lg.flex.items-center.h-10
     (when (and account-id chat) (:account/display-name (other-participant account-id chat)))
     [:div.flex-auto]
     [radix/dialog-close close-icon]]))

(q/defx db:mark-read!
  "Mark messages as read by account-id."
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id chat-id message-id]}]
  (ensure-participant! account-id (dl/entity chat-id))
  (db/transact! [[:db/add chat-id
                  :chat/read-last (merge (:chat/read-last (db/entity chat-id))
                                         {(sch/unwrap-id account-id) (sch/unwrap-id message-id)})]])
  nil)

(ui/defview chat-messages [{:as params :keys [other-id chat-id account-id]}]
  (let [{:as chat :chat/keys [messages]} (when chat-id (db:chat params))]
    (let [!message           (h/use-state nil)
          !response          (h/use-state nil)
          !scrollable-window (h/use-ref)
          message            (u/guard @!message (complement str/blank?))
          keydown-handler    (fn [e]
                               (when ((ui/keydown-handler
                                        {:Enter
                                         (fn [e]
                                           (reset! !response {:pending true})
                                           (p/let [response (db:new-message!
                                                              params
                                                              {:prose/format :prose.format/markdown
                                                               :prose/string message})]
                                             (reset! !response response)
                                             (when-not (:error response)
                                               (reset! !message nil))
                                             (js/setTimeout #(.focus (.-target e)) 10)))}) e)
                                 (.preventDefault e)))]
      (h/use-effect
        (fn []
          (when-let [el @!scrollable-window]
            (set! (.-scrollTop el) (.-scrollHeight el))))
        [(count messages) @!scrollable-window])
      (h/use-effect
        (fn []
          (when (unread? account-id chat)
            (db:mark-read! {:chat-id    (sch/wrap-id chat)
                            :message-id (sch/wrap-id (last messages))})))
        [(:entity/id (last messages))])
      [:<>
       [chat-header {:account-id account-id
                     :chat       chat}]
       [:div.flex-auto.overflow-y-scroll.flex-v.gap-3.p-2.border-t
        {:ref !scrollable-window}
        (->> messages
             (sort-by :entity/created-at)
             (map (partial chat-message params))
             doall)]
       [ui/auto-size
        {:class       [search-classes
                       "m-1 whitespace-pre-wrap min-h-[38px] flex-none"]
         :type        "text"
         :placeholder "Aa"
         :disabled    (:pending @!response)
         ;:style       {:max-height 200}
         :on-key-down keydown-handler
         :on-change   #(reset! !message (j/get-in % [:target :value]))
         :value       (or message "")}]])))

(ui/defview chats
  {:view/target :modal
   :route       "/chats"}
  [params]
  (let [chat-selected? (or (:chat-id params) (:other-id params))]
    [:div {:class ["h-screen w-screen md:h-[90vh] md:w-[80vw] max-w-[800px] flex"
                   "height-100p flex divide-x bg-gray-100"]}
     [:div.flex.flex-none.overflow-y-auto.w-48.md:w-64
      [chats-sidebar params]]
     [:div.flex.flex-auto.flex-col.relative.m-2
      {:class (when chat-selected? "rounded bg-white shadow")}
      (if chat-selected?
        [chat-messages params]
        [chat-header params])]]))

(routes/register-route chat
  {:alias-of chats
   :route    ["/chats/" ['entity/id :chat-id]]})

(routes/register-route new-chat
  {:alias-of chats
   :route    ["/chats/" ['entity/id :entity-id] "/new/" ['entity/id :other-id]]})