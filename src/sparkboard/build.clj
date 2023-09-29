(ns sparkboard.build
  (:require [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.repl.deps :as deps]
            [clojure.pprint :refer [pprint]]
            [re-db.api :as db]
            [sparkboard.routes :as routes]
            [sparkboard.transit :as t]
            [sparkboard.server.core]
            [sparkboard.migration.one-time :as one-time]))

(comment
  (require '[shadow.cljs.devtools.api :as shadow])
  (shadow/get-build-config :web)
  (shadow/compile :web)
  )

(defn start
  {:shadow/requires-server true}
  [port]
  ((requiring-resolve 'shadow.cljs.devtools.api/watch) :web)
  ((requiring-resolve 'sparkboard.server.core/-main) (Integer/parseInt port)))

(defn spit-changed [path s]
  (when (not= s (some-> (io/resource path) slurp))
    (spit path s)))

(defn spit-endpoints!
  {:shadow.build/stage :flush}
  [state]
  (spit-changed "resources/public/js/sparkboard-views.transit.json"
                (t/write
                  (routes/view-endpoints (:compiler-env state))))
  state)

(defn tailwind-dev!
  {:shadow.build/stage :flush}
  [state]
  (defonce _tsc (bp/process
                  {:in       :inherit
                   :out      :inherit
                   :err      :inherit
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
     {:main-class "sparkboard.server.core"
      :aliases    [:datalevin]})))


(comment

  (deps/sync-deps)



  ;; DATA IMPORT - copies to ./.db
  (one-time/fetch-mongodb)
  (one-time/fetch-firebase)
  (one-time/fetch-accounts)

  (defn try-transact! [txs]
    (doseq [tx txs]
      (try
        (db/transact! [tx])
        (catch Exception e
          (pprint [:failed tx])
          (throw e)))))


  (require '[sparkboard.migration.one-time :as one-time]
           '[datalevin.core :as dl]
           '[sparkboard.server.datalevin :as sd]
           '[sparkboard.server.env :as env]
           '[sparkboard.schema :as sb.schema])

  ;; reset db (may break fulltext index?)
  (do
    (dl/clear sd/conn)
    (alter-var-root #'sparkboard.server.datalevin/conn (constantly (dl/get-conn (env/db-path "datalevin") {})))
    (alter-var-root #'re-db.api/*conn* (constantly sd/conn)))

  (do

    (def entities (one-time/all-entities))


    ;; transact schema
    (db/merge-schema! @sb.schema/!schema)
    ;; upsert lookup refs
    (db/transact! (mapcat sb.schema/unique-keys entities))
    ;; transact entities
    (db/transact! (one-time/all-entities)))

  ;; add entities, print and stop if one fails
  (doseq [e entities]
    (try (db/transact! [e])
         (catch Exception e!
           (prn e)
           (throw e!))))
  *e

  (one-time/explain-errors!)

  (->> entities
       (filter (partial
                 one-time/contains-somewhere? #uuid "add545dd-2c20-3d45-944f-aaf3448b74a1")))


  *e

  )

;; TODO
;; playground for writing datalevin queries using plain english
;; - try ingesting the schema via ChatGPT 3.5, ada, babbage and use that to
;;   write queries (hopefully gpt4 isn't necessary)
;; https://github.com/wkok/openai-clojure