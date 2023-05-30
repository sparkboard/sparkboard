(ns sparkboard.entities.account 
  (:require [re-db.api :as db]
            [sparkboard.views.ui :as ui]
            [sparkboard.datalevin :as dl]))

(ui/defview read-view [params]
  [:pre (ui/pprinted (:data params))])

(defn read-query [params]
  ;; TODO, ensure that the account in params is the same as the logged in user
  (db/pull '[*
             {:member/_account [{:member/entity [:entity/title]}]}
             {:roles/_recipient [:roles/roles 
                                 {:roles/entity [:entity/kind 
                                                 :entity/title]}]}] 
           [:entity/id (:account params)]))