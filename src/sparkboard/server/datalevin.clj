(ns sparkboard.server.datalevin
  (:require [datalevin.core :as dl]
            [re-db.api :as db]
            [re-db.integrations.datalevin]
            [sparkboard.server.env :as env]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Setup


(do
  (def conn (dl/get-conn (env/db-path "datalevin") {}))
  (alter-var-root #'db/*conn* (constantly conn)))


(def entity? datalevin.entity/entity?)

(comment

  (dl/close conn)

  (do
    (dl/clear conn)
    (def conn (dl/get-conn (env/db-path "datalevin") {}))
    (alter-var-root #'db/*conn* (constantly conn))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Fulltext search


(defn entity-in-org?
  "Predicate fn, handy for search. Truthy iff given entity `ent` is within the organization identified by ID `oid`."
     ;; FIXME this may be a dead-end approach. consider implementing with datalog rules. however, also consider design ramifications of `search` instead of `q`.
  [org-id ent]
  (case (:entity/kind ent)
    :org (= org-id (:entity/id ent))
    :board (= org-id (:board/owner ent))
    :member (= org-id (-> ent :member/entity :board/owner))
    :project (= org-id (:project/board ent))
    false))

(def squuid  dl/squuid)

(defn now [] (java.util.Date.))

(defn q [query & inputs]
  (apply dl/q query @conn inputs))

(defn pull [expr id] (dl/pull @conn expr id))
(defn entid [id] (dl/entid @conn id))

(defn resolve-id [id] (entid (if (uuid? id)
                               [:entity/id id]
                               id)))

(defn entity [id] (dl/entity @conn id))


(defn transact! [txs]
  (dl/transact! conn txs))

(defn to-ref [m id-key] [id-key (id-key m)])
(def asset-ref #(to-ref % :asset/id))

(def kind->prefix* {:org          "a0"
                    :board        "a1"
                    :collection   "a2"
                    :member       "a3"
                    :project      "a4"
                    :field        "a5"
                    :entry        "a6"
                    :discussion   "a7"
                    :post         "a8"
                    :comment      "a9"
                    :notification "aa"
                    :tag          "ab"
                    :tag-spec     "ac"
                    :thread       "ad"
                    :message      "ae"
                    :roles        "af"
                    :account      "b0"
                    :ballot       "b1"
                    :site         "b2"
                    :asset        "b3"})

(def prefix->kind* (zipmap (vals kind->prefix*) (keys kind->prefix*)))

(defn uuid->kind [uuid]
  (let [prefix (subs (str uuid) 0 2)]
    (or (prefix->kind* prefix)
        (throw (ex-info (str "Unknown kind for uuid prefix " prefix) {:uuid uuid :prefix prefix})))))

(defn kind->prefix [kind]
  (or (kind->prefix* kind) (throw (ex-info (str "Invalid kind: " kind) {:kind kind}))))


(defn to-uuid [kind s]
  (java.util.UUID/fromString (str (kind->prefix kind) (subs (str (java.util.UUID/nameUUIDFromBytes (.getBytes s))) 2))))


(defn new-uuid [kind]
  (java.util.UUID/fromString (str (kind->prefix kind)
                                  (subs (str (random-uuid)) 2))))


(defn new-entity [m kind & {:keys [by]}]
  (-> m
      (merge #:entity{:id         (new-uuid kind)
                      :created-at (now)
                      :kind       kind})
      (cond-> by (assoc :entity/created-by by))))