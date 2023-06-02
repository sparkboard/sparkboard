(ns sparkboard.entities.org
  (:require  [sparkboard.datalevin :as dl]
             [applied-science.js-interop :as j]
             [inside-out.forms :as forms]
             [promesa.core :as p]
             [re-db.api :as db]
             [sparkboard.entities.domain :as domain]
             [sparkboard.i18n :refer [tr]]
             [sparkboard.routes :as routes]
             [sparkboard.validate :as validate]
             [sparkboard.views.ui :as ui]
             [sparkboard.websockets :as ws]
             [sparkboard.util :as u]
             [yawn.view :as v]
             [malli.util :as mu]))

(defn delete!
  "Mutation fn. Retracts organization by given org-id."
  [_req {:keys [org]}]
  ;; auth: user is admin of org
  ;; todo: retract org and all its boards, projects, etc.?
  (db/transact! [[:db.fn/retractEntity [:entity/id org]]])
  {:body ""})


(defn edit-query [params]
  ;; all the settings that can be changed
  (db/pull '[*
             {:entity/domain [:domain/name]}] [:entity/id (:org params)]))


(defn list-query [_]
  (->> (db/where [[:entity/kind :org]])
       (mapv (re-db.api/pull '[*
                               {:image/logo [:asset/id]}
                               {:image/background [:asset/id]}]))))

(defn read-query [params]
  (db/pull '[:entity/id
             :entity/kind
             :entity/title
             {:image/logo [:asset/id]}
             {:board/_org [:entity/created-at
                           :entity/id
                           :entity/kind
                           :entity/title]}
             {:entity/domain [:domain/name]}]
           [:entity/id (:org params)]))

(defn search-query [{:as         params 
                     :keys       [org]
                     {:keys [q]} :query-params}]
  (tap> [:search params])
  {:q        q
   :boards   (dl/q '[:find [(pull ?board [:entity/id
                                          :entity/title
                                          :entity/kind
                                          :image/logo
                                          :image/backgrouond
                                          {:entity/domain [:domain/name]}]) ...]
                     :in $ ?terms ?org
                     :where
                     [?board :board/org ?org]
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
                     [?board :board/org ?org]
                     [?project :project/board ?board]
                     [(fulltext $ ?terms {:top 100}) [[?project ?a ?v]]]]
                   q
                   [:entity/id org])})

(defn normalize-and-validate [org]
  (-> org
      (u/update-some-paths [:entity/domain :domain/name] domain/qualify-domain)
      (validate/assert (mu/optional-keys :org/as-map))))

(defn edit! [{:keys [account]} params org]
  (let [org (db/transact! [(normalize-and-validate org)])]
    {:body org}))

(defn new!
  [{:keys [account]} _ org]
  (let [org (-> org
                normalize-and-validate
                (dl/new-entity :org :by (:db/id account)))
        member (-> {:member/entity  org
                    :member/account (:db/id account)
                    :member/roles   #{:role/owner}}
                   (dl/new-entity :member))]
    (db/transact! [member])
    {:body org}))