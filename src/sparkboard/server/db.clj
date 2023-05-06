(ns sparkboard.server.db
  "Database queries and mutations (transactions)"
  (:require [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [sparkboard.server.validate :as sv]
            [sparkboard.datalevin :as sd]))

;; TODO
;; defquery can specify an out-of-band authorization fn that
;; can pass/fail the subscription based on the session without
;; including the session in the subscription args
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
  (->> (sd/q-fulltext-in-org (:q query-params)
                             id)
       ;; Can't send Entities over the wire, so:
       (map (db/pull '[:project/title
                       :board/title]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Mutations

;; TODO ...


;; - authorize mutations
;; - mutation fn should return a map with :tx and :ret keys
;; - validate newly created entities

(defn board:register [ctx registration-map]
  ;; create membership
  )

(defn tx-and-return! [entity schema]
  (sv/assert entity schema)
  (db/transact! [entity])
  {:body entity})

(defn project:create
  [req project]
  (sv/assert project [:map {:closed true} :project/title])
  ;; auth: user is member of board & board allows members to create projects
  (-> project
      (assoc :project/board [:sb/id (:board/id req)])
      (sd/new-entity :by (:db/id (:account req)))
      (tx-and-return! :project/as-map)))

(defn board:create
  [req board]
  (sv/assert board [:map {:closed true} :board/title])
  ;; auth: user is admin of org
  (-> board
      (assoc :board/org [:sb/id (:org/id req)])
      (sd/new-entity :by (:db/id (:account req)))
      (tx-and-return! :board/as-map)))

(defn org:create
  [req org]
  (sv/assert org [:map {:closed true} :org/title])
  ;; auth: ?
  (-> org
      (sd/new-entity :by (:db/id (:account req))
                     :legacy-id-key :org/id)
      (tx-and-return! :org/as-map)))

(defn org:delete
  "Mutation fn. Retracts organization by given org-id."
  [req {:keys [org/id]}]
  (sv/assert id :org/id)
  ;; auth: user is admin of org
  ;; todo: retract org and all its boards, projects, etc.?
  (db/transact! [[:db.fn/retractEntity [:org/id id]]])
  {:body ""})

