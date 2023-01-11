(ns org.sparkboard.datalevin
  (:require [datalevin.core :as dl]
            [org.sparkboard.server.env :as env]
            [re-db.api :as d]
            [re-db.integrations.datalevin]
            [re-db.read]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Setup

(def db-path (env/db-path "datalevin"))

(def conn (dl/get-conn db-path {}))

(alter-var-root #'re-db.read/*conn* (constantly conn))

(comment
 (dl/close conn)
 (dl/clear conn)


 (->> (d/where [:org/id])
      (map (d/pull '[*
                     :db/id
                     #_ {:board.settings/default-template [*]}
                     {:entity/domain [*]}
                     {:ts/created-by [*]}])))

 )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Queries

(defn qry-orgs []
  (d/where [:org/id]))

(defn qry-boards [org-id]
  (d/where [:board/id
            [:board/org [:org/id org-id]]]))


