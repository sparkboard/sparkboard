(ns sparkboard.entities.account 
  (:require [re-db.api :as db]
            [sparkboard.views.ui :as ui]
            [sparkboard.routes :as routes]
            [sparkboard.datalevin :as dl]))

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
     #_[:pre (ui/pprinted (:data params))]) 
  
  )

(defn read-query [params]
  ;; TODO, ensure that the account in params is the same as the logged in user
  (time
   (->> (db/pull '[:member/roles
                   {:member/_account [{:member/entity [:entity/id 
                                                       :entity/kind 
                                                       :entity/title 
                                                       {:image/logo [:asset/link
                                                                     :asset/id
                                                                     {:asset/provider [:s3/bucket-host]}]} 
                                                       {:image/background [:asset/link 
                                                                           :asset/id 
                                                                           {:asset/provider [:s3/bucket-host]}]}]}]}] 
                 [:entity/id (:account params)])
        :member/_account
        (map #(merge (dissoc % :member/entity)
                     (:member/entity %)))
        (group-by :entity/kind)))
  
  
  )