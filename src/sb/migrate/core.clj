(ns sb.migrate.core
  (:require [sb.migrate.one-time :as one-time]
            [re-db.api :as db]
            [sb.schema :as sch]
            [sb.app]
            [sb.util :as u]))

(defn fetch! []
  (u/timed `mongo (one-time/fetch-mongodb))
  (u/timed `firebase (one-time/fetch-firebase))
  (u/timed `accounts (one-time/fetch-accounts)))

(defn tx! []
  (let [entities (u/timed `entities (one-time/all-entities))]

    ;; transact schema
    (u/timed `schema (db/merge-schema! @sch/!schema))
    ;; upsert lookup refs
    (u/timed `transact1 (db/transact! (mapcat sch/unique-keys entities)))
    ;; transact entities
    (u/timed `transact2
             (let [entities (one-time/all-entities)
                   ;; for some reason transacting in chunks is faster
                   chunks (partition 1000 entities)]
               (println (count entities) "entities")
               (println (apply str (repeat (count chunks) "-")))
               (doto (apply + (for [chunk chunks]
                                (do (print ".")
                                    (flush)
                                    (-> (db/transact! chunk)
                                        :tx-data
                                        count))))
                 ((fn [_] (prn)))
                 (println "datoms"))))))

(comment
  (tx!)
  )
