(ns org.sparkboard.dev
  (:require [nextjournal.clerk :as clerk]
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


;; DATA IMPORT
(comment
 (one-time/fetch-mongodb) ;; copies to ./.db
 (one-time/fetch-firebase) ;; copies to ./.db
 (one-time/fetch-accounts)


 (def entities (one-time/all-entities))
 (db/merge-schema! sb.schema/sb-schema)
 ;; "upsert" lookup refs
 (db/transact! (mapcat sb.schema/unique-keys entities))

 (doseq [e entities]
   (try (db/transact! [e])
        (catch Exception e!
          (println e)
          (throw e!))))

 (keys sb.schema/sb-schema)

 (-> (db/where [:board/id])
      first
     :ts/created-at
     type)

 )

;; TODO
;; playground for writing datalevin queries using plain english
;; - try ingesting the schema via ChatGPT 3.5, ada, babbage and use that to
;;   write queries (hopefully gpt4 isn't necessary)
;; https://github.com/wkok/openai-clojure