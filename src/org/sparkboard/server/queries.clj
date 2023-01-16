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

(defn-memo $org:index [_]
  (q/reaction conn
    (->> (db/where [:org/id])
         (mapv (re-db.api/pull '[*])))))

(defn-memo $org:one [{:keys [org/id]}]
  (q/reaction conn
    (db/pull '[:org/title
               {:board/_org [:ts/created-at
                             :board/id
                             :board/title]}
               {:entity/domain [:domain/name]}]
             [:org/id id])))

(defn-memo $board:index [_route-params]
  (q/reaction conn
    (->> (db/where [:board/id])
         (mapv (re-db.api/pull '[:board/title])))))

(defn-memo $board:one [{:keys [board/id] :as _route-params}]
  (q/reaction conn
              (db/pull '[*
                         :board/title
                         :board.registration/open?
                         :board/title
                         {:project/_board [*]}
                         {:board/org [:org/title :org/id]}
                         {:entity/domain [:domain/name]}]
                       [:board/id id])))

(defn-memo $project:index [_route-params]
  ;; TODO
  ;; pagination
  ;; search queries
  (q/reaction conn
    (->> (db/where [:project/id])
         (take 20)
         (mapv (re-db.api/pull '[:project/title])))))

(defn-memo $project:one [{:keys [project/id]}]
  (q/reaction conn
    (db/pull '[*] [:project/id id])))

(defn-memo $member:one [{:keys [member/id]}]
  (q/reaction conn
    (db/pull '[*] [:member/id id])))

(defonce !list (atom ()))

(defn-memo $list-view [_]
  (sync/$values !list))


