(ns sparkboard.ui.header
  (:require #?(:cljs ["@radix-ui/react-popover" :as Popover])
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.app.chat :as chat]
            [sparkboard.i18n :as i :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.ui :as ui]
            [sparkboard.ui.icons :as icons]
            [sparkboard.ui.radix :as radix]
            [yawn.hooks :as h]
            [yawn.util :as yu]
            [sparkboard.util :as u]))

(defn btn [{:keys [icon href]}]
  [(if href :a :div)
   {:class ["inline-flex items-center"
            "hover:text-txt/60"]
    :href  href}
   icon])


#?(:cljs
   (defn lang-menu-content []
     (let [current-locale (i/current-locale)
           on-select      (fn [v]
                            (p/do
                              (i/set-locale! {:i18n/locale v})
                              (js/window.location.reload)))]
       (map (fn [lang]
              (let [selected (= lang current-locale)]
                [{:selected selected
                  :on-click (when-not selected #(on-select lang))}
                 (get-in i/dict [lang :meta/lect])]))
            (keys i/dict)))))

(ui/defview lang [classes]
  [:div.inline-flex.flex-row.items-center {:class ["hover:text-txt-faded"
                                                   classes]}
   (apply radix/dropdown-menu
          {:trigger [icons/languages "w-5 h-5"]}
          (lang-menu-content))])

(ui/defview chats-list []
  (let [params {:account-id (db/get :env/config :account-id)}]
    (->> (chat/db:chats-list params)
         (take 6)
         (map (partial chat/chat-snippet params)))))

(ui/defview chat [entity]
  (let [!open? (h/use-state false)]
    [:el Popover/Root
     {:open           @!open?
      :on-open-change #(reset! !open? %)}
     [:el Popover/Trigger
      [:div.btn-light.relative
       ;; unread-count bubble
       (when (some-> (:unread (chat/db:counts {})) #_(u/guard pos-int?))
         [:div
          {:class ["absolute top-[-5px] right-[-5px] "
                   "w-3 h-3 rounded-full"
                   "bg-blue-500"]}])
       [icons/chat-bubble-bottom-center-text "w-7 h-7"]]]
     [:el Popover/Portal
      {:container (yu/find-or-create-element :radix-modal)}
      [:Suspense {}
       [:el.bg-white.shadow.p-2.outline-none.rounded-lg Popover/Content
        {:align "end"
         :style {:width 360}}
        (when @!open?
          [:div.flex.flex-col
           (chats-list)
           [:a.bg-blue-100.hover:bg-blue-200.rounded.text-center.py-2.mt-2 {:href (routes/href `chat/chats)}
            "View all"]])]]]]))

(ui/defview account []
  (if-let [account (db/get :env/config :account)]
    (radix/dropdown-menu
      {:trigger [:div.flex.items-center [:img.rounded-full.h-8.w-8 {:src (ui/asset-src (:image/avatar account) :avatar)}]]}
      [{:on-click #(routes/set-path! 'sparkboard.app.account/home)} (tr :tr/home)]
      [{:on-click #(routes/set-path! 'sparkboard.server.accounts/logout)} (tr :tr/logout)]
      (into [{:sub?    true
              :trigger [icons/languages "w-5 h-5"]}] (lang-menu-content)))
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routes/href 'sparkboard.app.account/sign-in)} (tr :tr/sign-in)]))

(ui/defview entity* [{:as   entity
                      :keys [entity/title
                             image/avatar]} children]
  (let [entity-href (routes/entity entity :show)]
    (into [:div.entity-header
           (when avatar
             [:a.contents {:href entity-href}
              [:img.h-10.w-10
               {:src (ui/asset-src avatar :avatar)}]])
           [:a.contents {:href entity-href} [:h3 title]]
           [:div.flex-grow]]
          (concat children
                  [[chat entity]
                   [account]]))))

(defn entity [entity & children] (entity* entity children))