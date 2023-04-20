(ns sparkboard.build
  (:require [babashka.process :as bp]))

(defn start
  {:shadow/requires-server true}
  []
  ((requiring-resolve 'shadow.cljs.devtools.api/watch) :web)
  ((requiring-resolve 'sparkboard.server.core/-main)))

(defn tailwind-dev!
  {:shadow.build/stage :flush}
  [state]
  (defonce _tsc (bp/process
                 {:in :inherit
                  :out :inherit
                  :err :inherit
                  :shutdown bp/destroy-tree}
                 "npx tailwindcss -w -i src/sparkboard.css -o resources/public/sparkboard.css"))
  state)

(defn tailwind-release!
  {:shadow.build/stage :flush}
  [state]
  (bp/sh "npx tailwindcss -i src/sparkboard.css -o resources/public/sparkboard.css")
  state)


(comment
 (start)
 (clerk/clear-cache!))

(defn aot [_]
  (require 'sparkboard.server.core)
  (compile 'sparkboard.server.core))

(defn uberjar [_]
  (let [deps (clojure.edn/read-string (slurp "deps.edn"))]
    ((requiring-resolve 'uberdeps.api/package) deps "target/sparkboard.jar"
     {:main-class "sparkboard.server"
      :aliases [:datalevin]})))


(comment

 (require '[sparkboard.migration.one-time :as one-time]
          '[datalevin.core :as dl]
          '[re-db.api :as db]
          '[sparkboard.schema :as sb.schema])

 ;; DATA IMPORT - copies to ./.db
 (one-time/fetch-mongodb)
 (one-time/fetch-firebase)
 (one-time/fetch-accounts)

 ;; RESHAPED ENTITIES
 (def entities (one-time/all-entities))

 ;; COMMIT TO DB - note, you may wish to
 (dl/clear conn) ;; NOTE - after this, re-run `def conn` in sparkboard.datalevin

 (db/merge-schema! sb.schema/sb-schema)

 ;; "upsert" lookup refs
 (db/transact! (mapcat sb.schema/unique-keys entities))
 (db/transact! entities)

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

 (->> (db/where [[:member/id "509719852c845b0200000001"]])

      )

 )

;; TODO
;; playground for writing datalevin queries using plain english
;; - try ingesting the schema via ChatGPT 3.5, ada, babbage and use that to
;;   write queries (hopefully gpt4 isn't necessary)
;; https://github.com/wkok/openai-clojure