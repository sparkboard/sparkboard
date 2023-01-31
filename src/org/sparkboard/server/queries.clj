(ns org.sparkboard.server.queries
  (:require [org.sparkboard.datalevin :as sb.datalevin :refer [conn]]
            [re-db.api :as db]
            [re-db.memo :refer [defn-memo]]
            [re-db.reactive :as r]
            [re-db.read :as read]))

(defn transact! [txs]
  (read/transact! conn txs))

(defn-memo $org:index [_]
  (r/reaction
   (->> (db/where [:org/id])
        (mapv (re-db.api/pull '[*])))))

(defn-memo $org:one [{:keys [org/id]}]
  (r/reaction
   (db/pull '[:org/id
              :org/title
              {:board/_org [:ts/created-at
                            :board/id
                            :board/title]}
              {:entity/domain [:domain/name]}]
            [:org/id id])))

(defn-memo $board:index [_route-params]
  (r/reaction
   (->> (db/where [:board/id])
        (mapv (re-db.api/pull '[:board/title])))))

(defn-memo $board:one [{:keys [board/id] :as _route-params}]
  (r/reaction
   (db/pull '[*
              :board/title
              :board.registration/open?
              :board/title
              {:project/_board [*]}
              {:board/org [:org/title :org/id]}
              {:member/_board [*]}
              {:entity/domain [:domain/name]}]
            [:board/id id])))

(defn-memo $project:index [_route-params]
  (r/reaction
   ;; TODO
   ;; pagination
   ;; search queries
   (->> (db/where [:project/id])
        (take 20)
        (mapv (re-db.api/pull '[:project/title])))))

(defn-memo $project:one [{:keys [project/id]}]
  (r/reaction
   (db/pull '[*] [:project/id id])))

(defn-memo $member:one [{:keys [member/id]}]
  (r/reaction
   (db/pull '[*
              {:member/tags [*]}]
            [:member/id id])))

(defn-memo $search [{:keys [query-params org/id] :as route-params}]
  (r/reaction
   (->> (sb.datalevin/q-fulltext-in-org (:q query-params)
                                        id)
        ;; Can't send Entities over the wire, so:
        (map (db/pull '[:project/title
                        :board/title])))))

