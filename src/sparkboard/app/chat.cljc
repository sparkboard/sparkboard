(ns sparkboard.app.chat
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.app.member :as member]
            [sparkboard.authorize :as az]
            [sparkboard.schema :as sch :refer [s-]]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.ui :as ui]
            [sparkboard.util :as u]
            [sparkboard.query :as query]
            [sparkboard.routes :as routes]
            [yawn.view :as v]
            [sparkboard.ui.icons :as icons]
            [sparkboard.i18n :refer [tr]]
            [inside-out.macros :refer [if-found]]
            [re-db.reactive :as r]
            [re-db.memo :as memo]
            #?(:cljs [yawn.hooks :as h])))

(def search-classes
  (v/classes ["rounded-lg p-2 text-sm bg-gray-50 border-gray-300 border"
              "disabled:bg-gray-200 disabled:text-gray-500"]))

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

(query/defx db:new-message!
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
  (u/template
    [:entity/id
     :entity/updated-at
     :chat/read-last
     {:chat/entity [:entity/id
                    :entity/title
                    {:image/avatar [:asset/id]}]}
     {:chat/participants [:account/display-name
                          {:image/avatar [:asset/id]}
                          :entity/id]}]))

(def message-fields
  (u/template
    [:entity/id
     :entity/created-at
     {:entity/created-by [:entity/id]}
     {:chat.message/content [:prose/format
                             :prose/string]}]))

(defn ensure-participant!
  "Throw if account-id is not a participant in chat."
  [account-id chat]
  #?(:clj
     (do (when-not (contains?
                     (into #{}
                           (map :entity/id)
                           (:chat/participants chat))
                     (sch/unwrap-id account-id))
           (az/unauthorized! "You are not a participant in this chat."))
         chat)))

(query/defx db:mark-read!
  "Mark messages as read by account-id."
  {:prepare [az/with-account-id!]}
  [{:keys [account-id chat-id message-id]}]
  (ensure-participant! account-id (dl/entity message-id))
  (let [{:as chat :keys [chat/read-last]} (db/entity chat-id)]
    (db/transact! [[:db/add (:db/id chat)
                    :chat.message/read-last
                    (assoc read-last (sch/unwrap-id account-id)
                                     (sch/unwrap-id message-id))]]))
  true)

(def chat-fields-full
  (conj chat-fields-meta {:chat/messages message-fields}))

(query/defquery db:chat
  "Get a chat by chat-id"
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id chat-id]}]
  (some->> (db/pull chat-fields-full chat-id)
           (ensure-participant! account-id)))

(query/defquery db:chats-list
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id]}]
  (->> (db/entity account-id)
       :chat/_participants
       (sort-by :entity/updated-at u/compare:desc)
       (mapv (fn [e]
               (-> (db/pull chat-fields-meta e)
                   (assoc :chat/last-message
                          (db/pull message-fields
                                   (last (:chat/messages e)))))))))

(defn read? [account-id {:chat/keys [last-message read-last]}]
  (let [account-id (sch/unwrap-id account-id)]
    (or (= (get read-last account-id) (:entity/id last-message))
        (= account-id (:entity/id (:entity/created-by last-message))))))

(def unread? (complement read?))

(query/defquery db:counts
  {:prepare [az/with-account-id!]}
  [params]
  (let [chats @(query/$ db:chats-list params)]
    {:total  (count chats)
     :unread (->> chats
                  (filter (partial unread? (:account-id params)))
                  count)}))

(ui/defview member-search [params]
  (let [!search-term (h/use-state "")]
    [:<>
     [:div.flex.flex-col.relative.px-1.py-2
      [:input.w-full
       {:class       search-classes
        :value       @!search-term
        :placeholder (tr :tr/find-a-member)
        :on-change   #(reset! !search-term (j/get-in % [:target :value]))}]
      [:div.absolute.right-2.top-0.bottom-0.flex.items-center
       [icons/search "w-5 h-5 absolute right-2"]]]
     ;; TODO
     ;; show results in a dropdown/autocomplete
     (ui/pprinted
       (member/db:search (assoc params :search-term
                                       (h/use-deferred-value @!search-term))))]))

(defn other-participant [account-id chat]
  (first (remove #(sch/id= account-id %) (:chat/participants chat))))

(ui/defview chats-list [{:as             chat
                         :keys           [account-id]
                         current-chat-id :chat-id}]
  (let [chats (db:chats-list nil)]
    [:div.flex.flex-col.text-sm.divide-y.px-1.py-2
     (doall
       (for [{:as   chat
              :keys [entity/id
                     chat/participants
                     chat/last-message]} chats
             :let [other    (other-participant account-id chat)
                   current? (sch/id= current-chat-id id)]]
         [:a.flex.flex-col.py-2.cursor-default.mx-1.px-1
          {:href  (routes/href `chat {:chat-id id})
           :key   id
           :class (when current? "bg-blue-100 rounded")}
          [:div.flex.items-center.gap-2
           [:div.w-2.h-2.rounded-full
            {:class (if (and (unread? account-id chat) #_(not current?)) "bg-blue-500")}]
           ;; participants

           [:div.font-bold {:key (:entity/id other)} (:account/display-name other)]]
          [:div.pl-4.text-gray-700.hidden.md:line-clamp-2
           (ui/show-prose
             (cond-> (:chat.message/content last-message)
                     (sch/id= account-id (:entity/created-by last-message))
                     (update :prose/string (partial str (tr :tr/you) " "))))]]))]))

(ui/defview show-chat-message
  {:key (fn [_ {:keys [entity/id]}] id)}
  [{:keys [account-id]} {:keys [chat.message/content
                                entity/created-at
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

(ui/defview messages-pane [params]
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
                                         (p/let [response (query/effect! `db:new-message!
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
    [:<>
     [:div.p-2.border-b.text-lg (:account/display-name (other-participant (:account-id params) chat))]
     [:div.flex-auto.overflow-y-scroll.flex.flex-col.gap-3.p-2
      {:ref !scrollable-window}
      (->> messages
           (sort-by :entity/created-at)
           (map (partial show-chat-message params))
           doall)]
     [ui/auto-height-textarea

      {:class       [search-classes
                     "m-1 whitespace-pre-wrap min-h-[38px] flex-none"]
       :type        "text"
       :placeholder "Aa"
       :disabled    (:pending @!response)
       ;:style       {:max-height 200}
       :on-key-down keydown-handler
       :on-change   #(reset! !message (j/get-in % [:target :value]))
       :value       (or message "")}]]))

(ui/defview chats
  {:view/target :modal
   :route       "/chats"}
  [{:as params :keys [other-id chat-id]}]
  [:div {:class ["h-screen w-screen md:h-[80vh] md:w-[80vw] max-w-[800px] flex items-stretch"
                 "height-100p flex divide-x items-stretch"]}
   [:div {:class ["min-w-48 max-w-md w-1/3 overflow-y-auto"]}
    [chats-list params]]
   [:div.flex-auto.flex.flex-col.relative
    (when (or other-id chat-id)
      (ui/try
        [messages-pane params]
        (catch js/Error e (str (ex-message e)))))]])

(routes/register-route chat
  {:alias-of chats
   :route    ["/chats/" ['entity/id :chat-id]]})

(routes/register-route new-chat
  {:alias-of chats
   :route    ["/chats/" ['entity/id :entity-id] "/new/" ['entity/id :other-id]]})

(comment

  (memo/defn-memo $blah-atom [s]
    (let [s (str s "-atom")]
      (prn :INIT-ATOM s)
      (r/atom s {:on-dispose #(prn :DISPOSE-ATOM s)})))

  (memo/defn-memo $blah-reaction [s]
    (let [s (str s "-reaction")]
      (r/reaction
        (re-db.hooks/use-effect
          (fn []
            (prn :INIT-REACTION s)
            #(prn :DISPOSE-REACTION s)))
        s)))

  (ui/defview foo
    {:route       "/foo"
     :view/target :modal}
    [_]
    (let [!pick (h/use-state \a)]
      (h/use-effect (fn []
                      (prn :render "-------------------------")
                      (js/setTimeout #(prn "===================") 10)))
      [:div
       (str "pick: "
            {"atom: "     @($blah-atom @!pick)
             "reaction: " @($blah-reaction @!pick)})

       [:div.flex.gap-3
        (for [s "abcdefghijklmnop"]
          [:div.font-bold.text-xl {:key s :on-click #(reset! !pick s)} s]
          )]])
    ))