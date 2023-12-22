(ns sb.app.views.header
  (:require #?(:cljs ["@radix-ui/react-popover" :as Popover])
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.chat.data :as chat.data]
            [sb.app.chat.ui :as chat.ui]
            [sb.app.entity.ui :as entity.ui]
            [sb.i18n :as i :refer [t]]
            [sb.routing :as routes]
            [sb.app.views.ui :as ui]
            [sb.icons :as icons]
            [sb.app.views.radix :as radix]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.util :as yu]))

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
     {:trigger  [icons/languages]
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
        (t :tr/view-all)]]
      (t :tr/no-messages))))

(ui/defview chat []
  (let [!open? (h/use-state false)
        unread (some-> (:unread (chat.data/chat-counts {})) (u/guard pos-int?))]
    [:el Popover/Root
     {:open           @!open?
      :on-open-change #(reset! !open? %)}
     [:el Popover/Trigger {:as-child true}
      [:button {:tab-index 0}
       (when unread
         [:div.z-10.absolute.font-bold.text-xs.text-center.text-focus-accent
          {:style {:top 2 :right 0 :width 20}}
          unread])
       [icons/paper-plane (when unread "text-focus-accent")]]]
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
       {:trigger  [:button {:tab-index 0}
                   [:img.rounded-full.icon-lg {:src (asset.ui/asset-src (:image/avatar account) :avatar)}]]
        :children [[{:on-click #(routes/nav! 'sb.app.account-ui/show)} (t :tr/home)]
                   [{:on-click #(routes/nav! 'sb.app.account-ui/logout!)} (t :tr/logout)]
                   [{:sub?     true
                     :trigger  [icons/languages "w-5 h-5"]
                     :children (lang-menu-content)}]]})]
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routes/path-for ['sb.app.account-ui/sign-in])} (t :tr/continue-with-email)]))

(ui/defview entity [{:as   entity
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
                   [[chat]
                    [account]]))]))