(ns sparkboard.ui.header
  (:require #?(:cljs ["@radix-ui/react-popover" :as Popover])
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.app.chat :as chat]
            [sparkboard.app.entity :as entity]
            [sparkboard.i18n :as i :refer [tr]]
            [sparkboard.routing :as routes]
            [sparkboard.ui :as ui]
            [sparkboard.ui.icons :as icons]
            [sparkboard.ui.radix :as radix]
            [sparkboard.validate :as validate]
            [yawn.hooks :as h]
            [yawn.util :as yu]
            [sparkboard.util :as u]))

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
        chats  (chat/db:chats-list params)]
    (if (seq chats)
      [:div.flex-v
       (->> chats
            (take 6)
            (map (partial chat/chat-snippet params)))
       [:a.bg-blue-100.hover:bg-blue-200.rounded.text-center.py-2.mt-2.focus-ring
        {:href (routes/path-for [`chat/chats])}
        (tr :tr/view-all)]]
      (tr :tr/no-messages))))

(ui/defview chat []
  (let [!open? (h/use-state false)
        unread (some-> (:unread (chat/db:counts {})) (u/guard pos-int?))]
    [:el Popover/Root
     {:open           @!open?
      :on-open-change #(reset! !open? %)}
     [:el Popover/Trigger {:as-child true}
      [:button.relative.flex.items-center.icon-light-gray.px-1.rounded {:tab-index 0}
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
                   [:img.rounded-full.h-6.w-6 {:src (ui/asset-src (:image/avatar account) :avatar)}]]
        :children [[{:on-click #(routes/nav! 'sparkboard.app.account/show)} (tr :tr/home)]
                   [{:on-click #(routes/nav! 'sparkboard.app.account/logout!)} (tr :tr/logout)]
                   [{:sub?     true
                     :trigger  [icons/languages "w-5 h-5"]
                     :children (lang-menu-content)}]]})]
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routes/path-for ['sparkboard.app.account/sign-in])} (tr :tr/continue-with-email)]))

(ui/defview entity* [{:as   entity
                      :keys [entity/title
                             image/avatar]} children]
  (let [entity-href (routes/entity entity :show)]
    [:div.entity-header
     (when avatar
       [:a.contents {:href entity-href}
        [:img.h-10
         {:src (ui/asset-src avatar :avatar)}]])

     [:a.contents {:href entity-href} [:h3.hover:underline title]]

     [:div.flex-grow]
     (into [:div.flex.gap-1]
           (concat children
                   [(entity/settings-button entity)
                    [chat]
                    [account]]))]))

(defn entity [entity & children] (entity* entity children))