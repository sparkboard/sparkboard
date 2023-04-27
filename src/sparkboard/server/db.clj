(ns sparkboard.server.db
  "Database queries and mutations (transactions)"
  (:require [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [ring.util.http-response :as http-rsp]
            [sparkboard.datalevin :as sb.datalevin]))

(defmacro defquery
  "Defines a query function. The function will be memoized and return a {:value / :error} map."
  [name & fn-body]
  (let [[doc [argv & body]] (if (string? (first fn-body))
                              [(first fn-body) (rest fn-body)]
                              [nil fn-body])]
    `(memo/defn-memo ~name ~argv
       (r/reaction ~@body))))

(defquery $org:index [_]
  (->> (db/where [:org/id])
       (mapv (re-db.api/pull '[*]))))

(comment
 (db/transact! [{:org/id (str (rand-int 10000))
                 :org/title (str (rand-int 10000))}]))

(defquery $org:one [{:keys [org/id]}]
  (db/pull '[:org/id
             :org/title
             {:board/_org [:ts/created-at
                           :board/id
                           :board/title]}
             {:entity/domain [:domain/name]}]
           [:org/id id]))

(defquery $board:one [{:keys [board/id]}]
  (db/pull '[*
             :board/title
             :board/registration-open?
             :board/title
             {:project/_board [*]}
             {:board/org [:org/title :org/id]}
             {:member/_board [*]}
             {:entity/domain [:domain/name]}]
           [:board/id id]))

(defquery $project:one [{:keys [project/id]}]
  (db/pull '[*] [:project/id id]))

(defquery $member:one [{:keys [member/id]}]
  (dissoc (db/pull '[*
                     {:member/tags [*]}]
                   [:member/id id])
          :member/password))

(defquery $search [{:keys [query-params org/id]}]
  (->> (sb.datalevin/q-fulltext-in-org (:q query-params)
                                       id)
       ;; Can't send Entities over the wire, so:
       (map (db/pull '[:project/title
                       :board/title]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Mutations

(defn board:register [ctx params mbr]
  ;; create membership
  )

(defn project:create
  {:POST [:map {:closed true} :project/title]}
  [req project]
  (db/transact! [(assoc project :project/board
                                [:board/id (:board/id req)]
                                :ts/created-by (:db/id (:account req)))]))

(defn board:create
  {:POST [:map {:closed true} :board/title]}
  [req board]
  (db/transact! [(assoc board
                   :board/id (str (random-uuid))
                   :org/id (:org/id req)
                   :ts/created-by (:db/id (:account req)))]))

(defn org:create
  {:POST [:map {:closed true} :org/title]}
  [req org]
  (db/transact! [(assoc org
                   :org/id (str (random-uuid))
                   :ts/created-by (:db/id (:account req)))])
  (http-rsp/ok org))

(defn org:delete
  "Mutation fn. Retracts organization by given org-id."
  {:POST :org/id}
  [req org-id]
  (db/transact! [[:db.fn/retractEntity [:org/id org-id]]]))

