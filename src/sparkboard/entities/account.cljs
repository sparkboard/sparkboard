(ns sparkboard.entities.account
  (:require [re-db.api :as db]
            [sparkboard.routes :as routes]
            [sparkboard.views.ui :as ui]
            [sparkboard.i18n :refer [tr]]))

(ui/defview read-view [{:as params :keys [data]}]
  (ui/with-form [?pattern (when ?filter (str "(?i)" ?filter))]
    
    [:<> 
     [:div.entity-header.mb-6
      [:h3.header-title :tr/my-stuff ]
      [ui/filter-field ?filter]
      [:div.btn.btn-light {:on-click #(routes/set-path! :org/new)} :tr/new-org]]
     (for [[title entities] [[:tr/orgs (:org data)]
                             [:tr/boards (:board data)]
                             [:tr/projects (:project data)]]
           :let [results (sequence (ui/filtered ?pattern) entities)]
           :when (seq results)]
       
       [:div {:key title} 
        [:div.px-body.text-muted-foreground.font-medium.tracking-wide.uppercase title]
        (into [:div.card-grid]
              (map ui/entity-card)
              results)])]
    #_[:pre (ui/pprinted (:data params))]))