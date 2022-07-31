(ns org.sparkboard.datalevin
  (:require [datalevin.core :as d]
            [org.sparkboard.server.env :as env]
            [re-db.api :as db]
            [re-db.integrations.datalevin]
            [re-db.read]
            [re-db.schema :as s]))

(def db-path (env/db-path "datalevin"))

(def conn (d/get-conn db-path {}))

(alter-var-root #'re-db.read/*conn* (constantly conn))

(comment

 (d/close conn)
 (d/clear conn))