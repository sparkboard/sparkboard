(ns sparkboard.datalevin
  (:require #?(:clj [datalevin.core :as dl])
            #?(:clj [sparkboard.server.env :as env])
            #?(:clj [re-db.integrations.datalevin])
            [re-db.api :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Setup

#?(:clj
   (do
     (def conn (dl/get-conn (env/db-path "datalevin") {}))
     (alter-var-root #'db/*conn* (constantly conn))))

(comment
  (dl/close conn)

  (do
    (dl/clear conn)
    (def conn (dl/get-conn (env/db-path "datalevin") {}))
    (alter-var-root #'db/*conn* (constantly conn))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Fulltext search

#?(:clj
   (defn entity-in-org?
     "Predicate fn, handy for search. Truthy iff given entity `ent` is within the organization identified by ID `oid`."
     ;; FIXME this may be a dead-end approach. consider implementing with datalog rules. however, also consider design ramifications of `search` instead of `q`.
     [org-id ent]
     (case (:entity/kind ent)
       :org (= org-id (:entity/id ent))
       :board (= org-id (:board/org ent))
       :member (= org-id (-> ent :member/board :board/org))
       :project (= org-id (:project/board ent))
       false)))

(def squuid #?(:clj dl/squuid :cljs random-uuid))
(defn now [] #?(:cljs (js/Date.now)
                :clj  (java.util.Date.)))

(defn new-entity [m kind & {:keys [by]}]
  (-> m
      (merge #:entity{:id (squuid)
                      :created-at (now)
                      :kind kind})
      (cond-> by (assoc :entity/created-by by))))

(defn q [query & inputs]
  #?(:clj  (apply dl/q query inputs)
     :cljs (throw (ex-info "datalevin/q not implemented in cljs" {:query query :inputs inputs}))))

(comment
  (->> (db/where [:account/email])

       (map (comp distinct flatten (juxt :account/display-name
                                (comp (partial map :member/name) :member/_account))))

       (remove (comp #{1} count))
       (take 100)

       )
  )