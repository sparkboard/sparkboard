(ns sparkboard.entities.account
  (:require [re-db.api :as db]))

(defn lift-key [m k]
  (merge (dissoc m k)
         (get m k)))

(defn read-query [params]
  ;; TODO, ensure that the account in params is the same as the logged in user
  (let [entities (->> (db/pull '[{:member/_account [:member/roles
                                                    :member/last-visited
                                                    {:member/entity [:entity/id
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
                     (map  #(lift-key % :member/entity)))]
    (->> entities
         (group-by :entity/kind)
         (merge {:recents (->> entities 
                               (filter :member/last-visited)
                               (sort-by :member/last-visited #(compare %2 %1))
                               (take 5))}))))

