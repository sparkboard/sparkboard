(ns sparkboard.server.db
  "Database queries and mutations (transactions)"
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [sparkboard.server.validate :as sv]
            [sparkboard.datalevin :as sd]))

(defn qualify-domain [domain]
  (if (str/includes? domain ".")
    domain
    (str domain ".sparkboard.com")))

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
                 :entity/title (str (rand-int 10000))}]))

(defquery $org:view [{:keys [org/id]}]
  (db/pull '[:org/id
             :entity/title
             {:board/_org [:entity/created-at
                           :board/id
                           :entity/title]}
             {:entity/domain [:domain/name]}]
           [:org/id id]))

(defquery $board:view [{:keys [board/id]}]
  (db/pull '[*
             :entity/title
             :board/registration-open?
             :entity/title
             {:project/_board [*]}
             {:board/org [:entity/title :org/id]}
             {:member/_board [*]}
             {:entity/domain [:domain/name]}]
           [:board/id id]))

(defquery $project:view [{:keys [project/id]}]
  (db/pull '[*] [:project/id id]))

(defquery $member:view [{:keys [member/id]}]
  (dissoc (db/pull '[*
                     {:member/tags [*]}]
                   [:member/id id])
          :member/password))

(defquery $search [{:keys [query-params org/id]}]
  (->> (sd/q-fulltext-in-org (:q query-params)
                             id)
       ;; Can't send Entities over the wire, so:
       (map (db/pull '[:entity/title
                       :entity/title]))))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Mutations

;; TODO ...


;; - authorize mutations
;; - mutation fn should return a map with :tx and :ret keys
;; - validate newly created entities

(defn board:register [ctx _ registration-map]
  ;; create membership
  )

(defn project:new
  [req params project]
  (sv/assert project [:map {:closed true} :entity/title])
  ;; auth: user is member of board & board allows members to create projects
  (db/transact! [(-> project
                     (assoc :project/board [:entity/id (:board/id params)])
                     (sd/new-entity :by (:db/id (:account req))))]))

(defn board:new
  [req params board pull]
  (sv/assert board [:map {:closed true} :entity/title])
  ;; auth: user is admin of org
  (db/transact!
   [(-> board
        (assoc :board/org [:entity/id (:org/id params)])
        (sd/new-entity :by (:db/id (:account req))))])
  (db/pull pull))

(defn org:new
  [{:keys [account]} _ org]
  (let [org (update-in org [:entity/domain :domain/name] #(some-> % qualify-domain))
        _ (sv/assert org [:map {:closed true}
                          :entity/title
                          :entity/description
                          [:entity/domain [:map {:closed true}
                                           [:domain/name [:re #"^[a-z0-9-.]+.sparkboard.com$"]]]]])
        org (sd/new-entity org
              :by (:db/id account)
              :legacy-id :org/id)]
    (db/transact! [org])
    {:body org}))

(defn org:delete
  "Mutation fn. Retracts organization by given org-id."
  [_req {:keys [org/id]}]
  ;; auth: user is admin of org
  ;; todo: retract org and all its boards, projects, etc.?
  (db/transact! [[:db.fn/retractEntity [:org/id id]]])
  {:body ""})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers

(defn domain-availability
  [_ {:keys [domain]}]
  (let [domain (qualify-domain domain)]
    {:body {:available?
            (and (re-matches #"^[a-z0-9-.]+$" domain)
                 (nil? (db/get [:domain/name domain] :domain/name)))
            :domain domain}}))