(ns sparkboard.entities.account
  (:require [re-db.api :as db]))

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