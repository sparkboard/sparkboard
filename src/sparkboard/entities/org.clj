(ns sparkboard.entities.org
  (:require [malli.util :as mu]
            [re-db.api :as db]
            [sparkboard.datalevin :as dl]
            [sparkboard.entities.domain :as domain]
            [sparkboard.entities.entity :as entity]
            [sparkboard.entities.member :as member]
            [sparkboard.util :as u]
            [sparkboard.validate :as v]
            [re-db.reactive :as r]
            [re-db.hooks :as hooks]))

(defn delete!
  "Mutation fn. Retracts organization by given org-id."
  [_req {:keys [org-id]}]
  ;; auth: user is admin of org
  ;; todo: retract org and all its boards, projects, etc.?
  (db/transact! [[:db.fn/retractEntity [:entity/id org-id]]])
  {:body ""})


(defn edit-query [{:keys [org-id]}]
  ;; all the settings that can be changed
  (db/pull `[~@entity/fields]
           [:entity/id org-id]))

(defn read-query
  {:authorize (fn [req {:as params :keys [org-id]}]
                (member/read-and-log! org-id (:db/id (:account req)))
                ;; TODO make sure user has permission?
                params)}
  [{:as params :keys [org-id]}]
  (db/pull `[~@entity/fields
             {:board/_owner ~entity/fields}]
           (dl/resolve-id org-id)))

(defn search-query [{:as   params
                     :keys [org-id q]}]
  (when q
    {:q        q
     :boards   (dl/q (u/template
                       [:find [(pull ?board ~entity/fields) ...]
                        :in $ ?terms ?org
                        :where
                        [?board :board/owner ?org]
                        [(fulltext $ ?terms {:top 100}) [[?board ?a ?v]]]])
                     q
                     [:entity/id org-id])
     :projects (->> (dl/q (u/template
                            [:find [(pull ?project [~@entity/fields
                                                    :project/sticky?
                                                    {:project/board [:entity/id]}]) ...]
                             :in $ ?terms ?org
                             :where
                             [?board :board/owner ?org]
                             [?project :project/board ?board]
                             [(fulltext $ ?terms {:top 100}) [[?project ?a ?v]]]])
                          q
                          [:entity/id org-id])
                    (remove :project/sticky?))}))

(defn edit! [{:keys [account]} {:keys [org-id]
                                org :body}]
  (let [org (entity/conform (assoc org :entity/id org-id) :org/as-map)]
    (db/transact! [org])
    {:body org}))

(defn new!
  [{:keys [account]} {org :body}]
  (let [org    (-> (dl/new-entity org :org :by (:db/id account))
                   (entity/conform :org/as-map))
        member (-> {:member/entity  org
                    :member/account (:db/id account)
                    :member/roles   #{:role/owner}}
                   (dl/new-entity :member))]
    (db/transact! [member])
    {:body org}))