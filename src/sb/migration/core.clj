(ns sb.migration.core
  (:require [sb.migration.one-time :as one-time]
            [re-db.api :as db]
            [sb.schema :as sch]
            [sb.app]))

(defn fetch! []
  (one-time/fetch-mongodb)
  (one-time/fetch-firebase)
  (one-time/fetch-accounts))

(defn tx! []
  (let [entities (one-time/all-entities)]

    ;; transact schema
    (db/merge-schema! @sch/!schema)
    ;; upsert lookup refs
    (db/transact! (mapcat sch/unique-keys entities))
    ;; transact entities
    (-> (db/transact! (one-time/all-entities))
        :tx-data
        count
        (str " datoms"))))