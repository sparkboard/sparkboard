(ns sparkboard.server.db
  "Database queries and mutations (transactions)"
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [sparkboard.server.validate :as sv]
            [sparkboard.datalevin :as sd]
            [datalevin.core :as dl]))

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
  (->> (db/where [[:entity/kind :org]])
       (mapv (re-db.api/pull '[*]))))

(comment
  (db/transact! [{:entity/id (str (rand-int 10000))
                  :entity/title (str (rand-int 10000))}]))

(defquery $org:view [params]
  (db/pull '[:entity/id
             :entity/kind
             :entity/title
             {:board/_org [:entity/created-at
                           :entity/id
                           :entity/kind
                           :entity/title]}
             {:entity/domain [:domain/name]}]
           [:entity/id (:org params)]))

(defquery $board:view [params]
  (db/pull '[:entity/id
             :entity/kind
             :entity/title
             :board/registration-open?
             {:project/_board [*]}
             {:board/org [:entity/id
                          :entity/kind
                          :entity/title]}
             {:member/_board [*]}
             {:entity/domain [:domain/name]}]
           [:entity/id (:board params)]))

(defquery $project:view [params]
  (db/pull '[*] [:entity/id (:project params)]))

(defquery $member:view [params]
  (dissoc (db/pull '[*
                     {:member/tags [*]}]
                   [:entity/id (:member params)])
          :member/password))


(defn org-search [{:keys [org q]}]
  {:q q
   :boards (dl/q '[:find [(pull ?board [:entity/id
                                        :entity/title
                                        :entity/kind
                                        :entity/images
                                        {:entity/domain [:domain/name]}]) ...]
                   :in $ ?terms ?org
                   :where
                   [?board :board/org ?org]
                   [(fulltext $ ?terms {:top 100}) [[?board ?a ?v]]]]
                 @sd/conn
                 q
                 [:entity/id org])
   :projects (dl/q '[:find [(pull ?project [:entity/id
                                            :entity/title
                                            :entity/kind
                                            :entity/description
                                            :entity/images
                                            {:project/board [:entity/id]}]) ...]
                     :in $ ?terms ?org
                     :where
                     [?board :board/org ?org]
                     [?project :project/board ?board]
                     [(fulltext $ ?terms {:top 100}) [[?project ?a ?v]]]]
                   @sd/conn
                   q
                   [:entity/id org])})

(comment
  (org-search {:org #uuid "2d5f7dd9-e406-3af9-8316-bc220572ae68"
               :q "robo"})

  )
(memo/defn-memo $org:search [params] (r/reaction (org-search params)))

(comment
  ;; find boards in org by title
  (->> (dl/q '[:find [?result ...] #_#_#_?board ?a ?v
               :in $ ?terms ?o
               :where
               [?board :board/org ?o]
               [(fulltext $ ?terms {:top 3}) [?result ...] #_[[?board ?a ?v]]]]
             @sd/conn
             "Hacking"
             [:entity/id #uuid "5e36941b-3d85-3737-a815-16acd45edc50"]))

  ;; projects in org by title
  (time (->> (dl/q '[:find (pull ?project [:entity/title :entity/kind])
                     :in $ ?terms ?org
                     :where
                     [?board :board/org ?org]
                     [?project :project/board ?board]
                     [(fulltext $ ?terms {:top 100}) [[?project ?a ?v]]]]
                   @sd/conn
                   "diabetes"
                   [:entity/id #uuid "5e36941b-3d85-3737-a815-16acd45edc50"])
             #_(map (db/pull '[:entity/title :board/org])))))


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
                     (assoc :project/board [:entity/id (:entity/id params)])
                     (sd/new-entity :by (:db/id (:account req))))]))

(defn board:new
  [req params board pull]
  (sv/assert board [:map {:closed true} :entity/title])
  ;; auth: user is admin of :board/org
  (db/transact!
    [(-> board
         (assoc :board/org [:entity/id (:entity/id params)])
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
                           :legacy-id :entity/id)]
    (db/transact! [org])
    {:body org}))

(defn org:delete
  "Mutation fn. Retracts organization by given org-id."
  [_req {:keys [org]}]
  ;; auth: user is admin of org
  ;; todo: retract org and all its boards, projects, etc.?
  (db/transact! [[:db.fn/retractEntity [:entity/id org]]])
  {:body ""})

(defquery $org:settings [params]
  ;; all the settings that can be changed
  (db/pull '[*
             {:entity/domain [:domain/name]}] [:entity/id (:org params)]))

(defn org:settings [{:keys [account]} {:keys [org]} _payload]
  ;; merge payload with org, validate, transact
  )



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Handlers

(defn domain-availability
  [_ {:keys [domain]}]
  (let [domain (qualify-domain domain)]
    {:body {:available?
            (and (re-matches #"^[a-z0-9-.]+$" domain)
                 (nil? (db/get [:domain/name domain] :domain/name)))
            :domain domain}}))