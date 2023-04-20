(ns sparkboard.datalevin
  (:require [datalevin.core :as dl]
            [sparkboard.server.env :as env]
            [re-db.api :as db]
            [re-db.integrations.datalevin]
            [re-db.read]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Setup

(do
  (def conn (dl/get-conn (env/db-path "datalevin") {}))
  (alter-var-root #'re-db.api/*conn* (constantly conn)))

(comment
 (dl/close conn)
 (dl/clear conn)


 (dl/schema conn)


 (->> (db/where [:org/id])
      (map (db/pull '[*
                     :db/id
                     #_{:org/default-board-template [*]}
                     {:entity/domain [*]}
                     {:ts/created-by [*]}])))

 (->> (db/where [[:org/id "postcovid"]])
      (map (db/pull '[*
                     :db/id
                     :org/title
                     #_{:org/default-board-template [*]}
                     {:entity/domain [*]}
                     {:ts/created-by [*]}])))

 )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Fulltext search

(defn entity-in-org?
  "Predicate fn, handy for search. Truthy iff given entity `ent` is within the organization identified by ID `oid`."
  ;; FIXME this may be a dead-end approach. consider implementing with datalog rules. however, also consider design ramifications of `search` instead of `q`.
  [oid ent]
  (or (-> ent :org/id #{oid})
      (-> ent :board/org :org/id #{oid})
      (-> ent :member/board :board/org :org/id #{oid})
      (-> ent :project/board :board/org :org/id #{oid})))

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
         [?e :board/title ?board-title]
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
  (->> (db/where [:org/id])
       (mapv (db/pull '[*])))

  ;; Get entity for an org
  (dl/q '[:find ?e .
          :in $ ?org-id
          :where [?e :org/id ?org-id]]
        (dl/db conn)
        "30073ee5-ce10-43c8-ae1f-145d84e7a3ee")

  ;; Delete it
  (let [org {:org/id "30073ee5-ce10-43c8-ae1f-145d84e7a3ee",
             :org/title "dave4",
             :ts/created-by {:db/id 130077}}
        eid (dl/q '[:find ?e .
                    :in $ ?org-id
                    :where [?e :org/id ?org-id]]
                  (dl/db conn)
                  (:org/id org))]
    (db/transact! [[:db.fn/retractEntity eid]]))

  ;; FIXME fails if entity does not exist / has already been deleted
  
  )
