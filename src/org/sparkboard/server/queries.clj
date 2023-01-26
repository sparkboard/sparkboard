(ns org.sparkboard.server.queries
  (:require [org.sparkboard.datalevin :as sb.datalevin :refer [conn]]
            [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.read :as read]
            [re-db.sync :as sync]))

(defmacro defquery
  "Defines a query function. The function will be memoized and return a {:value / :error} map."
  [name & fn-body]
  (let [[doc [argv & body]] (if (string? (first fn-body))
                              [(first fn-body) (rest fn-body)]
                              [nil fn-body])]
    `(memo/defn-memo ~name ~argv
       (r/reaction
        (sync/try-value ~@body)))))

(defn transact! [txs]
  (db/with-conn conn
    (->> (db/transact! txs)
         (read/handle-report! conn))))

(defquery $org:index [_]
  (->> (db/where [:org/id])
       (mapv (re-db.api/pull '[*]))))

(defquery $org:one [{:keys [org/id]}]
  (db/pull '[:org/id
             :org/title
             {:board/_org [:ts/created-at
                           :board/id
                           :board/title]}
             {:entity/domain [:domain/name]}]
           [:org/id id]))

(defquery $board:index [_route-params]
  (->> (db/where [:board/id])
       (mapv (re-db.api/pull '[:board/title]))))

(defquery $board:one [{:keys [board/id] :as _route-params}]
  (db/pull '[*
             :board/title
             :board.registration/open?
             :board/title
             {:project/_board [*]}
             {:board/org [:org/title :org/id]}
             {:member/_board [*]}
             {:entity/domain [:domain/name]}]
           [:board/id id]))

(defquery $project:index [_route-params]
  ;; TODO
  ;; pagination
  ;; search queries
  (->> (db/where [:project/id])
       (take 20)
       (mapv (re-db.api/pull '[:project/title]))))

(defquery $project:one [{:keys [project/id]}]
  (db/pull '[*] [:project/id id]))

(defquery $member:one [{:keys [member/id]}]
  (db/pull '[*
             {:member/tags [*]}]
           [:member/id id]))

(defquery $search [{:keys [query-params org/id] :as route-params}]
  (->> (sb.datalevin/q-fulltext-in-org (:q query-params)
                                       id)
       ;; Can't send Entities over the wire, so:
       (map (db/pull '[:project/title
                       :board/title]))))

