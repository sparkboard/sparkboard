(ns sparkboard.entities.account
  (:require [sparkboard.routes :as routes]
            [sparkboard.views.ui :as ui]))

(ui/defview read-view [{:as params :keys [data]}]
  (ui/with-form [?pattern (when ?filter (str "(?i)" ?filter))]
    [:<> 
     [:div.entity-header
      [:h3.header-title :tr/orgs]
      [ui/filter-field ?filter]
      [:div.btn.btn-light {:on-click #(routes/set-path! :org/new)} :tr/new-org]]
     [:div.entity-header [:h3.header-title :tr/orgs]]
     (into [:div.card-grid]
           (comp
            (ui/filtered ?pattern)
            (map ui/entity-card))
           (:org data))
     [:div.entity-header [:h3.header-title :tr/boards]]
     (into [:div.card-grid]
           (comp
            (ui/filtered ?pattern)
            (map ui/entity-card))
           (:board data))
     [:div.entity-header [:h3.header-title :tr/projects]]
     (into [:div.card-grid]
           (comp
            (ui/filtered ?pattern)
            (map ui/entity-card))
           (:project data))
     
     ]
     #_[:pre (ui/pprinted (:data params))]))