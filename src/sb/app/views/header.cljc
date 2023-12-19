(ns sb.app.views.header
  (:require #?(:cljs ["@radix-ui/react-popover" :as Popover])
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.chat.data :as chat.data]
            [sb.app.chat.ui :as chat.ui]
            [sb.app.entity.ui :as entity.ui]
            [sb.i18n :as i :refer [tr]]
            [sb.routing :as routes]
            [sb.app.views.ui :as ui]
            [sb.icons :as icons]
            [sb.app.views.radix :as radix]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.util :as yu]))

(defn btn [{:keys [icon href]}]
  [(if href :a :div)
   {:class "btn-white"
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
   (radix/dropdown-menu
     {:trigger  [icons/languages "w-5 h-5"]
      :children (lang-menu-content)})])

(ui/defview chats-list []
  (let [params {:account-id (db/get :env/config :account-id)}
        chats  (chat.data/chats-list params)]
    (if (seq chats)
      [:div.flex-v
       (->> chats
            (take 6)
            (map (partial chat.ui/chat-snippet params)))
       [:a.bg-blue-100.hover:bg-blue-200.rounded.text-center.py-2.mt-2.focus-ring
        {:href (routes/path-for [`chat.ui/chats])}
        (tr :tr/view-all)]]
      (tr :tr/no-messages))))

(ui/defview chat []
  (let [!open? (h/use-state false)
        unread (some-> (:unread (chat.data/chat-counts {})) (u/guard pos-int?))]
    [:el Popover/Root
     {:open           @!open?
      :on-open-change #(reset! !open? %)}
     [:el Popover/Trigger {:as-child true}
      [:button.relative.menu-darken.px-1.rounded {:tab-index 0}
       ;; unread-count bubble
       (when unread
         [:div.z-10
          {:style {:width      10
                   :height     10
                   :top        "50%"
                   :margin-top -14
                   :right      2
                   :position   "absolute"}
           :class ["rounded-full"
                   "bg-focus-accent focus-visible:bg-black"]}])
       [icons/chat-bubble-left "icon-lg -mb-[2px]"]]]
     [:el Popover/Portal
      {:container (yu/find-or-create-element :radix-modal)}
      [:Suspense {}
       [:el.bg-white.shadow.p-2.outline-none.rounded-lg Popover/Content
        {:align "end"
         :style {:width 360}}
        (when @!open?
          (chats-list))]]]]))

(ui/defview account []
  (if-let [account (db/get :env/config :account)]
    [:<>
     (radix/dropdown-menu
       {:trigger  [:button.flex.items-center.focus-ring.rounded.px-1 {:tab-index 0}
                   [:img.rounded-full.h-6.w-6 {:src (asset.ui/asset-src (:image/avatar account) :avatar)}]]
        :children [[{:on-click #(routes/nav! 'sb.app.account-ui/show)} (tr :tr/home)]
                   [{:on-click #(routes/nav! 'sb.app.account-ui/logout!)} (tr :tr/logout)]
                   [{:sub?     true
                     :trigger  [icons/languages "w-5 h-5"]
                     :children (lang-menu-content)}]]})]
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routes/path-for ['sb.app.account-ui/sign-in])} (tr :tr/continue-with-email)]))

(ui/defview entity* [{:as   entity
                      :keys [entity/title
                             image/avatar]} children]
  (let [entity-href (routes/entity-path entity :show)]
    [:div.header
     (when avatar
       [:a.contents {:href entity-href}
        [:img.h-10
         {:src (asset.ui/asset-src avatar :avatar)}]])

     [:a.contents {:href entity-href} [:h3.hover:underline title]]

     [:div.flex-grow]
     (into [:div.flex.gap-1]
           (concat children
                   [(entity.ui/settings-button entity)
                    [chat]
                    [account]]))]))

(defn entity [entity & children] (entity* entity children))