(ns org.sparkboard.server.queries
  (:require [re-db.read :as read]
            [re-db.sync :as sync]
            [re-db.memo :refer [def-memo defn-memo]]
            [org.sparkboard.datalevin :as sb.datalevin :refer [conn]]
            [re-db.api :as db]))

(defn transact! [txs]
  (->> (db/transact! conn txs)
       (read/handle-report! conn)))

(defn-memo $org:index [_]
  (db/bound-reaction conn
    (sync/try-value
     (->> (db/where [:org/id])
          (mapv (re-db.api/pull '[*]))))))

(defn-memo $org:one [{:keys [org/id]}]
  (db/bound-reaction conn
    (sync/try-value
     (db/pull '[:org/id
                :org/title
                {:board/_org [:ts/created-at
                              :board/id
                              :board/title]}
                {:entity/domain [:domain/name]}]
              [:org/id id]))))

(defn-memo $board:index [_route-params]
  (db/bound-reaction conn
    (sync/try-value
     (->> (db/where [:board/id])
          (mapv (re-db.api/pull '[:board/title]))))))

(defn-memo $board:one [{:keys [board/id] :as _route-params}]
  (db/bound-reaction conn
    (sync/try-value
     (db/pull '[*
                :board/title
                :board.registration/open?
                :board/title
                {:project/_board [*]}
                {:board/org [:org/title :org/id]}
                {:member/_board [*]}
                {:entity/domain [:domain/name]}]
              [:board/id id]))))

(defn-memo $project:index [_route-params]
  ;; TODO
  ;; pagination
  ;; search queries
  (db/bound-reaction conn
    (sync/try-value
     (->> (db/where [:project/id])
          (take 20)
          (mapv (re-db.api/pull '[:project/title]))))))

(defn-memo $project:one [{:keys [project/id]}]
  (db/bound-reaction conn
    (sync/try-value
     (db/pull '[*] [:project/id id]))))

(defn-memo $member:one [{:keys [member/id]}]
  (db/bound-reaction conn
    (sync/try-value
     (db/pull '[*
                {:member/tags [*]}]
              [:member/id id]))))

(defn-memo $search [{:keys [query-params org/id] :as route-params}]
  (->> (sb.datalevin/q-fulltext-in-org (:q query-params)
                                       id)
       ;; Can't send Entities over the wire, so:
       (map (db/pull '[:project/title
                       :board/title]))
       (sync/try-value)
       (db/bound-reaction conn)))

