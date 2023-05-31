(ns sparkboard.datalevin
  (:require #?(:clj [datalevin.core :as dl])
            #?(:clj [sparkboard.server.env :as env])
            #?(:clj [re-db.integrations.datalevin]) 
            [clojure.string :as str]
            [re-db.api :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Setup

#?(:clj
   (do
     (def conn (dl/get-conn (env/db-path "datalevin") {}))
     (alter-var-root #'db/*conn* (constantly conn))))

#?(:clj 
   (def entity? datalevin.entity/entity?))
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
       :member (= org-id (-> ent :member/entity :board/org))
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
  #?(:clj  (apply dl/q query @conn inputs)
     :cljs (throw (ex-info "datalevin/q not implemented in cljs" {:query query :inputs inputs}))))

#?(:clj 
   (defn entity [id] (dl/entity @conn id)))

#?(:clj
   (defn transact! [txs]
     (dl/transact! conn txs)))

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

#?(:clj
   (defn to-uuid [kind s]
     (java.util.UUID/fromString (str (kind->prefix kind) (subs (str (java.util.UUID/nameUUIDFromBytes (.getBytes s))) 2)))))

