(ns org.sparkboard.server.resources
  (:require [re-db.reactive :as r]
            [re-db.query :as q]
            [re-db.read :as read]
            [re-db.sync :as sync]
            [re-db.memo :refer [def-memo defn-memo]]
            [org.sparkboard.datalevin :refer [conn]]
            [re-db.api :as db]))

(defn transact! [txs]
  (->> (db/transact! conn txs)
       (read/handle-report! conn)))

(defn org-index [_]
  (q/reaction conn
    (->> (db/where [:org/id])
         (mapv (re-db.api/pull '[*])))))

(defn org-view [{:keys [org/id]}]
  (q/reaction conn
    (db/pull '[*] [:org/id id])))

(defonce !list (atom ()))

(defn list-view [_]
  (sync/$values !list))