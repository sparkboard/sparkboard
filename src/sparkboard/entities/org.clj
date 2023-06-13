(ns sparkboard.entities.org
  (:require [malli.util :as mu]
            [re-db.api :as db]
            [sparkboard.datalevin :as dl]
            [sparkboard.entities.domain :as domain]
            [sparkboard.entities.entity :as entity]
            [sparkboard.entities.member :as member]
            [sparkboard.util :as u]
            [sparkboard.validate :as validate]))

(defn delete!
  "Mutation fn. Retracts organization by given org-id."
  [_req {:keys [org]}]
  ;; auth: user is admin of org
  ;; todo: retract org and all its boards, projects, etc.?
  (db/transact! [[:db.fn/retractEntity [:entity/id org]]])
  {:body ""})


(defn edit-query [params]
  ;; all the settings that can be changed
  (db/pull `[ ~@entity/fields] 
           [:entity/id (:org params)]))

(defn read-query 
  {:authorize (fn [req params] 
                (member/read-and-log! (:org params) (:db/id (:account req))))}
  [params]
  (db/pull `[~@entity/fields
             {:board/_org ~entity/fields}]
           (dl/resolve-id (:org params))))

(defn search-query [{:as         params 
                     :keys       [org]
                     {:keys [q]} :query-params}]
  {:q        q
   :boards   (dl/q '[:find [(pull ?board [:entity/id
                                          :entity/title
                                          :entity/kind
                                          :image/logo
                                          :image/backgrouond
                                          {:entity/domain [:domain/name]}]) ...]
                     :in $ ?terms ?org
                     :where
                     [?board :board/owner ?org]
                     [(fulltext $ ?terms {:top 100}) [[?board ?a ?v]]]]
                   q
                   [:entity/id org])
   :projects (dl/q '[:find [(pull ?project [:entity/id
                                            :entity/title
                                            :entity/kind
                                            :entity/description
                                            :image/logo
                                            :image/backgrouond
                                            {:project/board [:entity/id]}]) ...]
                     :in $ ?terms ?org
                     :where
                     [?board :board/owner ?org]
                     [?project :project/board ?board]
                     [(fulltext $ ?terms {:top 100}) [[?project ?a ?v]]]]
                   q
                   [:entity/id org])})

(defn edit! [{:keys [account]} params org]
  (let [org (entity/conform (assoc org :entity/id (:org params)) :org/as-map)]
    (db/transact! [org])
    {:body org}))

(defn new!
  [{:keys [account]} _ org]
  (let [org    (-> (dl/new-entity org :org :by (:db/id account))
                   (entity/conform :org/as-map))
        member (-> {:member/entity  org
                    :member/account (:db/id account)
                    :member/roles   #{:role/owner}}
                   (dl/new-entity :member))]
    (db/transact! [member])
    {:body org}))