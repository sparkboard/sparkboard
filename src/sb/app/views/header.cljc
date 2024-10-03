(ns sb.app.views.header
  (:require #?(:cljs ["@radix-ui/react-popover" :as Popover])
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.chat.data :as chat.data]
            [sb.app.chat.ui :as chat.ui]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.membership.data :as member.data]
            [sb.app.notification.data :as notification.data]
            [sb.app.notification.ui :as notification.ui]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.i18n :as i :refer [t]]
            [sb.icons :as icons]
            [sb.query :as q]
            [sb.routing :as routing]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.util :as yu]
            [yawn.view :as v]))

#?(:cljs
   (defn lang-menu-content []
     (let [current-locale (i/current-locale)
           on-select      (fn [v]
                            (p/do
                              (i/set-locale! {:i18n/locale v})
                              (js/window.location.reload)))]
       (mapv (fn [lang]
               (let [selected (= lang current-locale)]
                 [{:selected selected
                   :on-click (when-not selected #(on-select lang))}
                  (get-in i/dict [lang :meta/lect])]))
             (keys i/dict)))))

(ui/defview lang [classes]
  [:div.inline-flex.flex-row.items-center {:class ["hover:text-txt-faded"
                                                   classes]}
   (radix/dropdown-menu
     {:trigger [icons/languages]
      :items   (lang-menu-content)})])

(ui/defview chats-list []
  (let [chats  (chat.data/chats-list nil)]
    (if (seq chats)
      [:div.flex-v
       (->> chats
            (take 6)
            (map (partial chat.ui/chat-snippet nil #_params)))
       [:a.bg-blue-100.hover:bg-blue-200.rounded.text-center.py-2.mt-2.focus-ring
        {:href (routing/path-for [`chat.ui/chats])}
        (t :tr/view-all)]]
      [:div.flex-v
       (t :tr/no-messages)
       [:a.bg-blue-100.hover:bg-blue-200.rounded.text-center.py-2.mt-2.focus-ring
        {:href (routing/path-for [`chat.ui/chats])}
        (t :tr/view-all)]])))

(ui/defview notifications []
  (let [unread (some-> (:unread (notification.data/counts nil)) (u/guard pos-int?))]
    [radix/menubar-menu {:trigger [:button {:tab-index 0}
                                   (when unread
                                     [:div.z-10.absolute.font-bold.text-xs.text-center.text-focus-accent
                                      {:style {:top 2 :right 0 :width 20}}
                                      unread])
                                   [icons/bell (when unread "text-focus-accent")]]
                         :content [notification.ui/show-all]}]))

(ui/defview chat []
  (let [unread (some-> (:unread (chat.data/chat-counts {})) (u/guard pos-int?))]
    [radix/menubar-menu {:trigger (v/x [:button {:tab-index 0}
                                        (when unread
                                          [:div.z-10.absolute.font-bold.text-xs.text-center.text-focus-accent
                                           {:style {:top 2 :right 0 :width 20}}
                                           unread])
                                        [icons/paper-plane (when unread "text-focus-accent")]])
                         :content (v/x
                                    [:Suspense {}
                                     [:div.bg-white.p-2
                                      {:style {:width 360}}
                                      [chats-list]]])}]))

(ui/defview account []
  (if-let [account (db/get :env/config :account)]
    [:<>
     (radix/menubar-menu
       {:trigger [:button {:tab-index 0}
                  [:img.rounded.icon-lg {:src (asset.ui/asset-src (:image/avatar account) :avatar)}]]
        :items   [[{:on-click #(routing/nav! 'sb.app.account.ui/home)} (t :tr/home)]
                  [{:on-click #(routing/nav! 'sb.app.account.ui/logout!)} (t :tr/logout)]
                  [{:trigger [icons/languages "w-5 h-5"]
                    :items   (lang-menu-content)}]]})]
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routing/path-for ['sb.app.account-ui/sign-in])} (t :tr/continue-with-email)]))

(def down-arrow (icons/chevron-down:mini "ml-1 -mr-1 w-4 h-4"))

(ui/defview recents []
  (when-let [entities (-> (:value (q/use [`member.data/descriptions {:ids @routing/!recent-ids}]))
                          ui/use-last-some)]
    (when (seq entities)
      (radix/menubar-menu
        {:trigger [:button (t :tr/recent) down-arrow]
         :items   (mapv (fn [entity]
                          [{:on-select #(routing/nav! (routing/entity-route entity 'ui/show) entity)}
                           (or (:entity/title entity)
                               (:account/display-name (or (:membership/member entity)
                                                          entity)))])
                        entities)}))))

(ui/defview entity [{:as   entity
                     :keys [entity/title
                            image/avatar]} children]
  (let [path (routing/entity-path entity 'ui/show)]
    [:div.header
     (when avatar
       (h/use-effect #(set! (.-href (.querySelector js/document "link[rel~='icon']"))
                            (asset.ui/asset-src avatar :avatar))
                     (h/use-deps avatar))
       [:a {:href path}
        [:img.h-10
         {:src (asset.ui/asset-src avatar :avatar)}]])
     [:a.hover:underline.text-xl.font-medium.text-ellipsis.truncate.self-center {:href path} title]

     [:div.flex-grow]
     [:div.flex.gap-1
      (entity.ui/settings-button entity)
      [radix/menubar-root {:class "contents"}
       (concat children
               [(recents)
                (radix/menubar-menu
                  {:trigger [:button (t :tr/new) down-arrow]
                   :items   [[{:on-select #(routing/nav! 'sb.app.board.ui/new)} (t :tr/board)]
                             [{:on-select #(routing/nav! 'sb.app.org.ui/new)} (t :tr/org)]]})
                [notifications]
                [chat]
                [account]])]]]))
