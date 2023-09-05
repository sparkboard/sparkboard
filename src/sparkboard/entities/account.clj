(ns sparkboard.entities.account
  (:require [re-db.api :as db]
            [sparkboard.entities.entity :as entity]))

(defn lift-key [m k]
  (merge (dissoc m k)
         (get m k)))

(defn read-query
  {:authorize (fn [req params]
                (assoc params :account-id (-> req :account :entity/id)))}
  [{:as params :keys [account-id]}]
  ;; TODO, ensure that the account in params is the same as the logged in user
  (let [entities (->> (db/pull '[{:member/_account [:member/roles
                                                    :member/last-visited
                                                    {:member/entity [:entity/id
                                                                     :entity/kind
                                                                     :entity/title
                                                                     {:image/avatar [:asset/link
                                                                                     :asset/id
                                                                                     {:asset/provider [:s3/bucket-host]}]}
                                                                     {:image/background [:asset/link
                                                                                         :asset/id
                                                                                         {:asset/provider [:s3/bucket-host]}]}]}]}]
                               [:entity/id account-id])
                      :member/_account
                      (map #(lift-key % :member/entity)))
        recents  (->> entities
                      (filter :member/last-visited)
                      (sort-by :member/last-visited #(compare %2 %1))
                      (take 8))]
    (merge {:recents recents}
           (group-by :entity/kind entities))))

(defn orgs-query 
  {:authorize (fn [req params]
                ;; TODO if no account, fail unauthenticated
                (assoc params :account-id (-> req :account :entity/id)))}
  [{:keys [account-id]}]
  (into []
        (comp (map :member/entity)
              (filter (comp #{:org} :entity/kind))
              (map (db/pull entity/fields)))
        (db/where [[:member/account [:entity/id account-id]]])))

