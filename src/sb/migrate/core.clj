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
    (-> (u/timed `transact2 (db/transact! (one-time/all-entities)))
        :tx-data
        count
        (str " datoms"))))

(comment
  (tx!)
  )
