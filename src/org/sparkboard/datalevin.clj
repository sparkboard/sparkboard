(ns org.sparkboard.datalevin
  (:require re-db.integrations.datalevin
            [datalevin.core :as d]
            [re-db.read ]
            [org.sparkboard.server.env :as env]
            [re-db.api :as db]
            [re-db.schema :as schema]))

(def db-path (str (env/env-var :DATALEVIN_DIR "./.datalevin")
                  "/2022_06_23"))

(def conn (d/get-conn db-path {}))

(alter-var-root #'re-db.read/*conn* (constantly conn))

(comment

 (db/merge-schema! {:name schema/unique-id})

 ;; check behaviour when transacting collections/maps
 (do (db/transact! [{:name "Matt"
                     :q [3 "a" {:q :b} #{:D}]}])
     (-> (db/where [[:name "Matt"]])
         first
         :q)))