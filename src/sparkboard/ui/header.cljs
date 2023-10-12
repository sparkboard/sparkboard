(ns sparkboard.ui.header
  (:require [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.app.chat :as chat]
            [sparkboard.i18n :as i :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.ui :as ui]
            [sparkboard.ui.icons :as icons]
            [sparkboard.ui.radix :as radix]))

(defn btn [{:keys [icon href]}]
  [:a.inline-flex.items-center {:class "hover:text-txt/60"
                                :href  href}
   icon])

(defn lang-menu-content []
  (let [current-locale (i/current-locale)
        on-select      (fn [v]
                         (p/do (routes/POST :account/set-locale v)
                               (js/window.location.reload)))]
    (map (fn [lang]
           (let [selected (= lang current-locale)]
             [{:selected selected
               :on-click (when-not selected #(on-select lang))}
              (get-in i/dict [lang :meta/lect])]))
         (keys i/dict))))

(ui/defview lang [classes]
  [:div.inline-flex.flex-row.items-center {:class ["hover:text-txt-faded"
                                                   classes]}
   (apply radix/dropdown-menu
          {:trigger [icons/languages "w-5 h-5"]}
          (lang-menu-content))])

(ui/defview chat [entity]
  [btn {:icon [icons/chat-bubble-bottom-center-text "w-6 h-6"]
        :href (routes/href `chat/chats {:entity/id (:entity/id entity)})}])

(ui/defview account []
  (if-let [account (db/get :env/account)]
    (radix/dropdown-menu
      {:trigger [:div.flex.items-center [:img.rounded-full.h-8.w-8 {:src (ui/asset-src (:image/avatar account) :avatar)}]]}
      [{:on-click #(routes/set-path! 'sparkboard.app.account/home)} (tr :tr/home)]
      [{:on-click #(routes/set-path! 'sparkboard.server.accounts/logout)} (tr :tr/logout)]
      (into [{:sub?    true
              :trigger [icons/languages "w-5 h-5"]}] (lang-menu-content)))
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routes/href 'sparkboard.app.account/sign-in)} (tr :tr/sign-in)]))

(defn entity [{:as          entity
                      :keys [entity/title
                             image/avatar]} & children]
  (let [entity-href (routes/entity entity :read)]
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