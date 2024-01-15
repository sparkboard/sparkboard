(ns sb.app.views.header
  (:require #?(:cljs ["@radix-ui/react-popover" :as Popover])
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.chat.data :as chat.data]
            [sb.app.chat.ui :as chat.ui]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.membership.data :as member.data]
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
  (let [params {:account-id (db/get :env/config :account-id)}
        chats  (chat.data/chats-list params)]
    (if (seq chats)
      [:div.flex-v
       (->> chats
            (take 6)
            (map (partial chat.ui/chat-snippet params)))
       [:a.bg-blue-100.hover:bg-blue-200.rounded.text-center.py-2.mt-2.focus-ring
        {:href (routing/path-for [`chat.ui/chats])}
        (t :tr/view-all)]]
      (t :tr/no-messages))))

(ui/defview chat-old []
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
        :items   [[{:on-click #(routing/nav! 'sb.app.account-ui/show)} (t :tr/home)]
                  [{:on-click #(routing/nav! 'sb.app.account-ui/logout!)} (t :tr/logout)]
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
                           (:entity/title entity)])
                        entities)}))))

(ui/defview entity [{:as   entity
                     :keys [entity/title
                            image/avatar]} children]
  (let [path (routing/entity-path entity 'ui/show)]
    [:div.header
     (when avatar
       [:a {:href path}
        [:img.h-10
         {:src (asset.ui/asset-src avatar :avatar)}]])
     [:a.hover:underline.text-xl.font-semibold.text-ellipsis.truncate.self-center {:href path} title]

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
                [chat]
                [account]])]]]))