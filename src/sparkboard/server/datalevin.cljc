(ns sparkboard.server.datalevin
  (:require #?(:clj [datalevin.core :as dl])
            #?(:clj [datalevin.search-utils :as sut])
            #?(:clj [re-db.integrations.datalevin])
            #?(:clj [sparkboard.server.env :as env])
            [sparkboard.schema :as sch]
            [re-db.api :as db]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Setup

#?(:clj
   (do

     (def db-opts (let [analyzer (sut/create-analyzer
                                   {:tokenizer
                                    (sut/create-regexp-tokenizer
                                      #"[\s:/\.;,!=?\"'()\[\]{}|<>&@#^*\\~`]+")
                                    :token-filters [(sut/create-ngram-token-filter 2)]})]
                    {:search-opts
                     {:query-analyzer analyzer
                      :analyzer       analyzer}}))

     (def conn (dl/get-conn (env/db-path "datalevin") {} db-opts))
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

#?(:clj
   (def squuid dl/squuid))

(defn now [] #?(:clj  (java.util.Date.)
                :cljs (js/Date.)))

#?(:clj
   (defn q [query & inputs]
     (apply dl/q query @conn inputs)))

#?(:clj
   (defn pull [expr id] (dl/pull @conn expr id)))

(defn entid [id]
  #?(:clj (dl/entid @conn id)
     :cljs id))


(defn resolve-id [id]
  (entid (sch/wrap-id id)))

(defn entity [id]
  #?(:clj  (dl/entity @conn (resolve-id id))
     :cljs (db/entity id)))

#?(:clj
   (defn transact! [txs]
     (dl/transact! conn txs)))

(defn to-ref [m id-key] [id-key (id-key m)])
(def asset-ref #(to-ref % :entity/id))



#?(:clj
   (defn to-uuid [kind s]
     (java.util.UUID/fromString (str (sch/kind->prefix kind) (subs (str (java.util.UUID/nameUUIDFromBytes (.getBytes s))) 2)))))

#?(:clj
   (defn new-uuid [kind]
     (java.util.UUID/fromString (str (sch/kind->prefix kind)
                                     (subs (str (random-uuid)) 2)))))

#?(:clj
   (defn new-entity [m kind & {:keys [by]}]
     (-> m
         (merge #:entity{:id         (new-uuid kind)
                         :created-at (now)
                         :kind       kind})
         (cond-> by (assoc :entity/created-by by)))))