(ns sparkboard.build
  (:require [babashka.process :as bp]
            [clojure.java.io :as io]
            [clojure.pprint :refer [pprint]]
            [clojure.repl.deps :as deps]
            [clojure.pprint :refer [pprint]]
            [clojure.walk :as walk]
            [re-db.api :as db]
            [sparkboard.routes :as routes]
            [sparkboard.transit :as t]
            [sparkboard.server.core]
            [sparkboard.migration.one-time :as one-time]
            [sparkboard.util :as u]))

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

(defn slurp-some [path]
  (try (slurp path)
       (catch Exception e nil)))

(defn spit-changed [path s]
  (when (not= s (slurp-some path))
    (println "changed: " path)
    (io/make-parents path)
    (spit path s)))

(defn spit-endpoints!
  {:shadow.build/stages #{:compile-prepare
                          :flush}}
  [state]
  (let [endpoints (routes/endpoints)]
    (spit-changed "resources/public/js/sparkboard-views.transit.json"
                  (t/write endpoints))
    (routes/init-endpoints! endpoints))
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

  ;; add entities, print and stop if one fails
  (doseq [e entities]
    (try (db/transact! [e])
         (catch Exception e!
           (prn e)
           (throw e!))))
  (->> (db/where [[:field/type :field.type/video]])
       (mapcat :board/_project-fields)
       (map :entity/title)
       )
  *e

  (one-time/explain-errors!)

  (->> entities
       (filter (partial
                 one-time/contains-somewhere? (read-string "#:prose{:format :prose.format/markdown, :string \"--- how is financial literacy transmitted?\\n * from someone who is financially literate;\\n\\t\\tstudents? issue of trust/privacy,\\n\\t\\tvolunteer from the bank,\\n\\t*books\\n\\t*on campus/hall, there is a presence where bankers can show their face and answer questions\\n\\t*workshops with a big public (e.g. UBS at glocals.com): give a group presentation, have account managers lined up\\n\\t*coaching\\n\\t*games, brings ease, relaxation\\n\\t*banks presence/helpdesk\"}"))))


  *e
  (start "3000")
  )