(ns org.sparkboard.datalevin
  (:require [datalevin.core :as d]
            [org.sparkboard.server.env :as env]
            [re-db.api :as db]
            [re-db.integrations.datalevin]
            [re-db.read]
            [re-db.schema :as schema]))

(def db-path (env/db-path "datalevin"))

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