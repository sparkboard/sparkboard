(ns org.sparkboard.server.queries
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

(defn-memo $org-index [_]
  (q/reaction conn
    (->> (db/where [:org/id])
         (mapv (re-db.api/pull '[*])))))

(defn-memo $org-view [{:keys [org/id]}]
  (q/reaction conn
    (db/pull '[*
               {:board/_org [:ts/created-at
                             :board/title]}]
             [:org/id id])))

(defn-memo $board-index [_]
  (q/reaction conn
    (->> (db/where [:board/id])
         (mapv (re-db.api/pull '[:board/title])))))

(defn-memo $project-index [_]
  ;; TODO
  ;; pagination
  ;; search queries
  (q/reaction conn
    (->> (db/where [:project/id])
         (take 20)
         (mapv (re-db.api/pull '[:project/title])))))

(defn-memo $project-view [{:keys [project/id]}]
  (q/reaction conn
    (db/pull '[*] [:project/id id])))

(defn-memo $member-view [{:keys [member/id]}]
  (q/reaction conn
    (db/pull '[*] [:member/id id])))

(defonce !list (atom ()))

(defn-memo $list-view [_]
  (sync/$values !list))


