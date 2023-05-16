(ns sparkboard.datalevin
  (:require [clojure.string :as str]
            [datalevin.core :as dl]
            [sparkboard.server.env :as env]
            [re-db.api :as db]
            [re-db.integrations.datalevin]
            [re-db.read]
            [re-db.schema :as sch]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Setup

(do
  (def conn (dl/get-conn (env/db-path "datalevin") {}))
  (alter-var-root #'re-db.api/*conn* (constantly conn)))

(comment
 (dl/close conn)
 (dl/clear conn)

 (:account/email (dl/schema conn))


 (db/merge-schema! {:account/email (merge sch/unique-id
                                          sch/string)})


 (->> (db/where [:entity/id])
      (map (db/pull '[*
                      :db/id
                      #_{:org/default-board-template [*]}
                      {:entity/domain [*]}
                      {:entity/created-by [*]}])))

 (->> (db/where [[:entity/id "postcovid"]])
      (map (db/pull '[*
                      :db/id
                      :entity/title
                      #_{:org/default-board-template [*]}
                      {:entity/domain [*]}
                      {:entity/created-by [*]}])))

 )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Fulltext search

(defn entity-in-org?
  "Predicate fn, handy for search. Truthy iff given entity `ent` is within the organization identified by ID `oid`."
  ;; FIXME this may be a dead-end approach. consider implementing with datalog rules. however, also consider design ramifications of `search` instead of `q`.
  [oid ent]
  (or (-> ent :entity/id #{oid})
      (-> ent :board/org :entity/id #{oid})
      (-> ent :member/board :board/org :entity/id #{oid})
      (-> ent :project/board :board/org :entity/id #{oid})))

(defn q-fulltext
  "Query using fulltext search on given String of `terms`.
  Returns seq of EAV tuples."
  [terms]
  (dl/q '[:find ?e ?a ?v
          :in $ ?terms
          :where [(fulltext $ ?terms)
                  [[?e ?a ?v]]]]
        (dl/db conn)
        terms))

(defn q-fulltext-in-org
  "Org-wide query using fulltext search on given String of `terms`.
  Returns seq of re-db Entities."
  ;; TODO incorporate org clauses into query directly instead of filtering in app layer
  [terms org-id]
  (->> (q-fulltext terms)
       (map (comp db/entity first))
       (filter (partial entity-in-org? org-id))))


(comment

 (dl/q '[:find ?v
         :in $ ?board-title
         :where
         [?e :entity/title ?board-title]
         [?e ?a ?v]]
       (dl/db conn)
       "Tout commence par une idée!")

 (->> (dl/q '[:find ?e ?a ?v
              :in $ ?terms
              :where [(fulltext $ ?terms)
                      [[?e ?a ?v]]]]
            (dl/db conn)
            #_"masa"
            "idée innovante"
            #_"innovante")
      (map (comp db/entity first))
      (filter (partial entity-in-org? "robo-avatar" #_"opengeneva"))
      (map #(into {} %)))

 (q-fulltext-in-org "masa" "opengeneva")

 ;; TODO CIDER print handler for re-db entities

 )

(comment ;;;; how to delete?
 ;; DAL created a bunch of dummy orgs, to test front-end reactivity &
 ;; mutations. Now they need to go away.

 ;; See all orgs so I can pick out the ones to delete
 (->> (db/where [:entity/id])
      (mapv (db/pull '[*])))

 ;; Get entity for an org
 (dl/q '[:find ?e .
         :in $ ?org-id
         :where [?e :entity/id ?org-id]]
       (dl/db conn)
       "30073ee5-ce10-43c8-ae1f-145d84e7a3ee")

 ;; Delete it
 (let [org {:entity/id "30073ee5-ce10-43c8-ae1f-145d84e7a3ee",
            :entity/title "dave4",
            :entity/created-by {:db/id 130077}}
       eid (dl/q '[:find ?e .
                   :in $ ?org-id
                   :where [?e :entity/id ?org-id]]
                 (dl/db conn)
                 (:entity/id org))]
   (db/transact! [[:db.fn/retractEntity eid]]))

 ;; FIXME fails if entity does not exist / has already been deleted

 )

(def squuid dl/squuid)
(defn now [] (java.util.Date.))

(defn new-entity [m & {:keys [by legacy-id]}]
  (let [id (squuid)]
    (-> m
        (assoc :entity/id id)
        (cond-> legacy-id (assoc legacy-id (str id)))
        (assoc :entity/created-at (now))
        (cond-> by (assoc :entity/created-by by)))))