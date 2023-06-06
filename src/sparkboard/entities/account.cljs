(ns sparkboard.entities.account
  (:require [sparkboard.client.views :as views]
            [sparkboard.entities.entity :as entity]
            [sparkboard.routes :as routes]
            [sparkboard.views.ui :as ui]))

(ui/defview read-view [{:as params :keys [data]}]
  (ui/with-form [?pattern (when ?filter (str "(?i)" ?filter))]
    [:<> 
     [:div.entity-header
      [:h3 :tr/my-stuff ]
      [ui/filter-field ?filter]
      [:div.btn.btn-light {:on-click #(routes/set-path! :org/new)} :tr/new-org]
      [views/header:account]]
     
     
     (for [[title entities] [[:tr/recently-viewed (:recents data)]
                             [:tr/orgs (:org data)]
                             [:tr/boards (:board data)]
                             [:tr/projects (:project data)]]
           :let [results (sequence (ui/filtered ?pattern) entities)]
           :when (seq results)]
       
       [:div.mt-6 {:key title} 
        [:div.px-body.font-medium.tracking-wide.uppercase.text-muted-txt title]
        (into [:div.card-grid]
              (map entity/card)
              results)])]
    #_[:pre (ui/pprinted (:data params))]))