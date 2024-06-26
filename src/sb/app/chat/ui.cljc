(ns sb.app.chat.ui
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [promesa.core :as p]
            [sb.app.chat.data :as data]
            [sb.app.field.ui :as field.ui]
            [sb.app.membership.data :as member.data]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.authorize :as az]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(def entity-fields [:entity/id :entity/created-at :entity/updated-at])

(def search-classes
  (v/classes ["rounded-lg p-2 bg-gray-50 border-gray-300 border"
              "disabled:bg-gray-200 disabled:text-gray-500"]))

(ui/defview member-search [params]
  (let [!search-term (h/use-state "")]
    [:<>
     [:div.flex-v.relative.px-1.py-2
      [:input.w-full
       {:class       search-classes
        :value       @!search-term
        :placeholder (t :tr/find-a-member)
        :on-change   #(reset! !search-term (j/get-in % [:target :value]))}]
      [:div.absolute.right-2.top-0.bottom-0.flex.items-center
       [icons/search "w-5 h-5 absolute right-2"]]]
     (doall
       (for [account (member.data/search {:search-term (h/use-deferred-value @!search-term)})]
         [:div.flex.items-center.gap-2.font-bold
          [ui/avatar {:size 8} account]
          (:account/display-name account)]))]))

(defn other-participant [account-id chat]
  (let [membership-id (:db/id (az/membership account-id (:chat/entity chat)))]
    (u/find-first (:chat/participants chat)
                  #(not= membership-id (:db/id %)))))

(ui/defview chat-snippet
  {:key (fn [_ {:keys [entity/id]}] id)}
  [{:as   params
    :keys [current-chat-id
           account-id]} chat]
  (let [{:as   chat
         :keys [entity/id
                chat/last-message]} chat
        other    (other-participant account-id chat)
        current? (sch/id= current-chat-id id)]
    [:a.flex.gap-2.py-2.cursor-default.px-1.rounded.items-start.text-sm.w-full.text-left.focus-bg-gray-100
     {:href  (routing/path-for [`chat {:chat-id id}])
      :class (if current? "bg-blue-100 rounded" "hover:bg-gray-100")}
     [ui/avatar {:size 12 :class "flex-none"} (:membership/member other)]
     [:div.flex-v.w-full.overflow-hidden
      [:div.flex.items-center
       [:div.font-bold.flex-auto (:account/display-name (:membership/member other))]]
      [:div.text-gray-700.hidden.md:line-clamp-2.text-sm
       {:class (when (data/unread? params chat) "font-semibold")}
       (field.ui/show-prose
         (cond-> (:chat.message/content last-message)
                 (sch/id= account-id (:entity/created-by last-message))
                 (update :prose/string (partial str (t :tr/you) " "))))]]]))

(ui/defview chats-sidebar [{:as             chat
                            :keys           [account-id]
                            current-chat-id :chat-id}]
  [:div.flex-v.px-1.py-2.w-full
   #_[member-search nil]
   (->> (data/chats-list nil)
        (map (partial chat-snippet {:current-chat-id current-chat-id
                                    :account-id      account-id})))])

(ui/defview chat-message
  {:key (fn [_ {:keys [entity/id]}] id)}
  [{:keys [membership-id]} {:keys [chat.message/content
                                   entity/created-by
                                   entity/id]}]
  [:div.p-2.flex-v
   {:class ["max-w-[600px] rounded-[12px]"
            (if (sch/id= membership-id created-by)
              "bg-blue-500 text-white place-self-end"
              "bg-gray-100 text-gray-900 place-self-start")]
    :key   id}
   (field.ui/show-prose content)])

(ui/defview chat-header [{:keys [account-id chat]}]
  (let [close-icon [icons/close "w-4 h-4 ml-2 hover:opacity-50 flex-none"]]
    [:div.p-2.text-lg.flex.items-center.h-14.w-full.flex-none
     [:div.truncate.flex-auto
      (when (and account-id chat)
        (-> (other-participant account-id chat)
            :membership/member
            :account/display-name))]
     [radix/dialog-close close-icon]]))

(ui/defview chat-messages [{:as params :keys [other-id chat-id account-id]}]
  (let [{:as chat :chat/keys [messages]} (when chat-id (data/chat params))
        current-membership (->> (:chat/participants chat)
                                (filter (comp #{(sch/unwrap-id account-id)}
                                              :entity/id
                                              :membership/member))
                                first
                                (sch/wrap-id))]
    (let [!message           (h/use-state nil)
          !response          (h/use-state nil)
          !scrollable-window (h/use-ref)
          message            (u/guard @!message (complement str/blank?))
          params             (assoc params :membership-id current-membership)
          keydown-handler    (fn [e]
                               (when ((ui/keydown-handler
                                        {:Enter
                                         (fn [e]
                                           (reset! !response {:pending true})
                                           (p/let [response (data/new-message!
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
          (when (data/unread? params chat)
            (data/mark-read! {:chat-id    (sch/wrap-id chat)
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
       [field.ui/auto-size
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
  {:view/router :router/modal
   :route       "/chats"}
  [params]
  (let [chat-selected? (or (:chat-id params) (:other-id params))]
    [:div {:class ["h-screen w-screen md:h-[90vh] md:w-[80vw] max-w-[800px] flex"
                   "height-100p flex divide-x bg-gray-100"]}
     [:div.flex.flex-none.overflow-y-auto.w-48.md:w-64
      [chats-sidebar params]]
     [:div.flex-v.overflow-hidden.flex-auto.relative.m-2
      {:class (when chat-selected? "rounded bg-white shadow")}
      (if chat-selected?
        [chat-messages params]
        [chat-header params])]]))

(routing/register-route chat
                        {:alias-of    chats
                         :route       "/chats/:chat-id"
                         :view/router :router/modal})

(routing/register-route new-chat
                        {:alias-of    chats
                         :route       "/chats/new/:other-id"
                         :view/router :router/modal})