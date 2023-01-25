(ns org.sparkboard.datalevin
  (:require [datalevin.core :as dl]
            [org.sparkboard.server.env :as env]
            [re-db.api :as d]
            [re-db.integrations.datalevin]
            [re-db.read]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Setup

(def conn (dl/get-conn (env/db-path "datalevin") {}))

(alter-var-root #'re-db.read/*conn* (constantly conn))

(comment
 (dl/close conn)
 (dl/clear conn)


 (->> (d/where [:org/id])
      (map (d/pull '[*
                     :db/id
                     #_ {:board.settings/default-template [*]}
                     {:entity/domain [*]}
                     {:ts/created-by [*]}])))

 (->> (d/where [[:org/id "postcovid"]])
      (map (d/pull '[*
                     :db/id
                     :org/title
                     #_ {:board.settings/default-template [*]}
                     {:entity/domain [*]}
                     {:ts/created-by [*]}])))
 
 )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Queries

(defn qry-orgs []
  (d/where [:org/id]))

(defn qry-boards [org-id]
  (d/where [:board/id
            [:board/org [:org/id org-id]]]))



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
       (map (comp d/entity first))
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
       (map (comp d/entity first))
       (filter (partial entity-in-org? "robo-avatar" #_"opengeneva"))
       (map #(into {} %)))

  (q-fulltext-in-org "masa" "opengeneva")
  
  ;; TODO CIDER print handler for re-db entities
  
  )
