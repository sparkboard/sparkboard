(ns org.sparkboard.dev
  (:require [datalevin.core :as dl]
            [nextjournal.clerk :as clerk]
            [nextjournal.clerk.config :as config]
            [org.sparkboard.migration.one-time :as one-time]
            [org.sparkboard.schema :as sb.schema]
            [org.sparkboard.server :as server]
            [re-db.api :as db]
            [shadow.cljs.devtools.api :as shadow]))

(defn start
  {:shadow/requires-server true}
  []
  #_(shadow/watch :clerk)
  #_(swap! config/!resource->url merge {"/js/viewer.js" "http://localhost:3001/clerk.js"})
  #_(clerk/serve! {:browse? true
                   :port 7999
                   :watch-paths ["dev/org/sparkboard"]})

  (shadow/watch :web)
  (server/restart-server! 3000))

(comment
 (start)
 (clerk/clear-cache!))



(comment

 ;; DATA IMPORT
 (one-time/fetch-mongodb) ;; copies to ./.db
 (one-time/fetch-firebase) ;; copies to ./.db
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

 ;; NOTE: you may get an 'environment mapsize reached' error,
 ;;       this does not mean there is an error in the code,
 ;;       seems to be an issue with datalevin/lmdb.
 ;;       somehow things can start working again for no reason.

 (one-time/explain-errors!)

 (keys sb.schema/sb-schema)

 (->> (db/where [[:member/id  "509719852c845b0200000001"]])

      )

 )

;; TODO
;; playground for writing datalevin queries using plain english
;; - try ingesting the schema via ChatGPT 3.5, ada, babbage and use that to
;;   write queries (hopefully gpt4 isn't necessary)
;; https://github.com/wkok/openai-clojure