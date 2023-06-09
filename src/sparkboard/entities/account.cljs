(ns sparkboard.entities.account
  (:require [sparkboard.client.views :as views]
            [sparkboard.entities.entity :as entity]
            [sparkboard.routes :as routes]
            [sparkboard.views.ui :as ui]
            [yawn.hooks :as h]
            [sparkboard.i18n :as i]))

(ui/defview read-view [{:as params :keys [data]}]
  (let [!tab (h/use-state :tr/recent)]
    (ui/with-form [?pattern (when ?filter (str "(?i)" ?filter))]
      (let [show-results (fn [title results]
                            (when-let [results (seq (sequence (ui/filtered ?pattern) results))]
                              [:div.mt-6 {:key title}
                               (when title [:div.px-body.font-medium title [:hr.mt-2.sm:hidden]])
                               (into [:div.card-grid]
                                     (map entity/card)
                                     results)]))
            tab (if @?pattern :tr/search @!tab)
            select-tab! #(do (reset! ?filter nil)
                             (reset! !tab %))]
        [:<>
         [:div.entity-header
          [:h3 :tr/my-stuff]
          [ui/filter-field ?filter]
          [:div.btn.btn-light {:on-click #(routes/set-path! :org/new)} :tr/new-org]
          [views/header:account]]

         ;; tabs
         (->>  [:tr/recent
                :tr/all]
              (map (fn [k]
                     [:div {:on-click #(select-tab! k)
                            :class    (when-not (= k tab) "text-txt/50 hover:underline cursor-pointer")} k]))
               (into [:div.mt-6.flex.px-body.gap-2]))
         (if (= tab :tr/recent)
           (show-results nil (:recents data))
           [:<>
            (show-results :tr/orgs (:org data))
            (show-results :tr/boards (:board data))
            (show-results :tr/projects (:project data))])]))))