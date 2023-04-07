(ns org.sparkboard.dev
  (:require [datalevin.core :as dl]
            [org.sparkboard.migration.one-time :as one-time]
            [org.sparkboard.schema :as sb.schema]
            [org.sparkboard.server :as server]
            [re-db.api :as db]
            [shadow.cljs.devtools.api :as shadow]))

(defn start
  {:shadow/requires-server true}
  []
  (shadow/watch :web)
  (server/restart-server! 3000))


(comment

 (start)

 ;; DATA IMPORT - copies to ./.db
 (one-time/fetch-mongodb)
 (one-time/fetch-firebase)
 (one-time/fetch-accounts)

 ;; RESHAPED ENTITIES
 (def entities (one-time/all-entities))

 ;; COMMIT TO DB - note, you may wish to
 (dl/clear conn) ;; NOTE - after this, re-run `def conn` in org.sparkboard.datalevin

 (db/merge-schema! sb.schema/sb-schema)

 ;; "upsert" lookup refs
 (db/transact! (mapcat sb.schema/unique-keys entities))

 ;; add entities, print and stop if one fails
 (doseq [e entities]
   (try (db/transact! [e])
        (catch Exception e!
          (prn e)
          (throw e!))))

 (one-time/explain-errors!))