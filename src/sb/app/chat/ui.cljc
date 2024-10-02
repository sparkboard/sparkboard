(ns sb.app.chat.ui
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [promesa.core :as p]
            [re-db.api :as db]
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

(def search-classes
  (v/classes ["rounded-lg p-2 bg-gray-50 border-gray-300 border"
              "disabled:bg-gray-200 disabled:text-gray-500"]))

(defn unread? [{:notification/keys [unread-by]}]
  (some (partial sch/id= (db/get :env/config :account-id)) unread-by))

(ui/defview chat-snippet
  {:key (fn [_ {:keys [entity/id chat.message/recipient]}]
          (or id (:entity/id recipient)))}
  [params
   {:as   message
    :keys [entity/created-by
           chat.message/content
           chat.message/recipient]}]
  (let [other    (if (sch/id= recipient (db/get :env/config :account-id))
                   created-by
                   recipient)
        current? (sch/id= other (:other-id params))]
    [:a.flex.gap-2.py-2.cursor-default.px-1.rounded.items-start.text-sm.w-full.text-left.focus-bg-gray-100
     {:href  (routing/path-for [`chat {:other-id (:entity/id other)}])
      :class (if current? "bg-blue-100 rounded" "hover:bg-gray-200")}
     [ui/avatar {:size 12} other]
     [:div.flex-v.w-full.overflow-hidden
      [:div.flex.items-center
       [:div.font-bold.flex-auto (:account/display-name other)]]
      [:div.text-gray-700.text-sm.truncate
       {:class (when (unread? message) "font-semibold")}
       (field.ui/truncated-prose
        (cond-> content
          (sch/id= created-by (db/get :env/config :account-id))
          (update :prose/string (partial str (t :tr/you) " "))))]]]))

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
      (for [account
            (member.data/search-membership-account {:search-term (h/use-deferred-value @!search-term)})]
        [chat-snippet params (if-let [chat (data/chat {:other-id (sch/wrap-id account)})]
                               (last chat)
                               {:chat.message/recipient account})]))]))

(ui/defview chats-sidebar [params]
  [:div.flex-v.px-1.py-2.w-full
   [member-search params]
   (->> (data/chats-list nil)
        (map (partial chat-snippet params)))])

(ui/defview chat-message
  {:key (fn [_ {:keys [entity/id]}] id)}
  [{:keys [account-id]} {:keys [chat.message/content
                                entity/created-by
                                entity/id]}]
  [:div.p-2.flex-v
   {:class ["rounded-[12px]"
            (if (sch/id= account-id created-by)
              "bg-blue-500 text-white place-self-end ml-8"
              "bg-gray-100 text-gray-900 place-self-start mr-8")]
    :key   id}
   (field.ui/show-prose content)])

(ui/defview chat-messages [{:as params :keys [other-id account-id]}]
  (let [messages           (data/chat {:other-id other-id})
        !message           (h/use-state nil)
        !response          (h/use-state nil)
        !scrollable-window (h/use-ref)
        message            (u/guard @!message (complement str/blank?))
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
       (when-let [ids (not-empty (into []
                                       (comp (filter unread?)
                                             (map sch/wrap-id))
                                       messages))]
         (data/mark-read! {:message-ids ids})))
     [(:entity/id (last messages))])
    [:<>
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
       :value       (or message "")}]]))

(ui/defview chats
  {:view/router :router/modal
   :route       "/chats"}
  [{:keys [other-id] :as params}]
  [:div {:class ["h-screen w-screen md:h-[90vh] md:w-[80vw] max-w-[800px] flex"
                 "height-100p flex divide-x bg-gray-100"]}
   [:div.flex.flex-none.overflow-y-auto.w-48.md:w-64
    [chats-sidebar params]]
   [:div.flex-v.overflow-hidden.flex-auto.relative.m-2
    {:class (when other-id "rounded bg-white shadow")}
    [:div.p-2.text-lg.flex.items-center.h-14.w-full.flex-none
     (let [account (db/entity other-id)]
       [:a.truncate.flex-auto
        {:href (routing/entity-path account 'ui/show)}
        (:account/display-name account)])
     [radix/dialog-close [icons/close "w-4 h-4 ml-2 hover:opacity-50 flex-none"]]]
    (when other-id
      [chat-messages params])]])

(routing/register-route chat
                        {:alias-of    chats
                         :route       "/chats/:other-id"
                         :view/router :router/modal})
