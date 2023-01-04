(ns org.sparkboard.datalevin
  (:require [datalevin.core :as dl]
            [org.sparkboard.server.env :as env]
            [re-db.api :as d]
            [re-db.integrations.datalevin]
            [re-db.read]))

(def db-path (env/db-path "datalevin"))

(def conn (dl/get-conn db-path {}))

(alter-var-root #'re-db.read/*conn* (constantly conn))

(comment
 (dl/close conn)
 (dl/clear conn)


 (->> (d/where [:org/id])
      (map (d/pull '[*
                     #_ {:board.settings/default-template [*]}
                     {:entity/domain [*]}
                     {:ts/created-by [*]}])))

 
 (->> (d/where [:board/id])
      (map (d/pull '[(:board/id :db/id true)
                     :board/title
                     {:project/_board [:project/title]}]))
      (drop 3)
      first)


 )