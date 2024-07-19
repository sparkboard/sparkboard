(ns sb.app.stats.data
  (:require [sb.authorize :as az]
            [sb.query :as q]
            [sb.server.datalevin :as dl]
            [sb.util :as u]
            [re-db.api :as db])
  #?(:clj (:import [java.util Date])))

#?(:clj
   (do

     (def rules
       '[[(created-in-year ?e ?year)
          [?e :entity/created-at ?created-at]
          [(.getYear ^Date ?created-at) ?year*]
          [(+ ?year* 1900) ?year]]
         [(active? ?e)
          (not (or [?e :board/is-template? true]
                   [?e :entity/archived? true]
                   [?e :entity/deleted-at]))]])

     (defn count-of-kind [kind]
       (dl/q '[:find (count ?e) .
               :in $ % ?kind
               :where
               [?e :entity/kind ?kind]
               (active? ?e)]
             rules
             kind))

     (defn count-boards-by-org []
       (->> (dl/q '[:find ?org-name (count ?b)
                    :in $ %
                    :where
                    [?b :entity/kind :board]
                    [?b :entity/parent ?org]
                    [?org :entity/kind :org]
                    [?org :entity/title ?org-name]
                    (active? ?b)]
                  rules)
            (into {})))

     (defn count-boards-by-org-by-year []
       (-> (dl/q '[:find ?org-name ?year (count ?b)
                   :in $ %
                   :where
                   [?b :entity/kind :board]
                   [?b :entity/parent ?org]
                   [?org :entity/kind :org]
                   [?org :entity/title ?org-name]
                   (created-in-year ?b ?year)
                   (active? ?b)]
                 rules)
           (->> (group-by first))
           (update-vals (partial into {} (map #(subvec % 1))))))

     (defn count-projects-by-org []
       (->> (dl/q '[:find ?org-name (count ?p)
                    :in $ %
                    :where
                    [?p :entity/kind :project]
                    [?p :entity/parent ?b]
                    [?b :entity/kind :board]
                    [?b :entity/parent ?org]
                    [?org :entity/kind :org]
                    [?org :entity/title ?org-name]
                    (active? ?p)]
                  rules)
            (into {})))

     (defn count-projects-by-org-by-year []
       (-> (dl/q '[:find ?org-name ?year (count ?p)
                   :in $ %
                   :where
                   [?p :entity/kind :project]
                   [?p :entity/parent ?b]
                   [?b :entity/kind :board]
                   [?b :entity/parent ?org]
                   [?org :entity/kind :org]
                   [?org :entity/title ?org-name]
                   (created-in-year ?p ?year)
                   (active? ?p)]
                 rules)
           (->> (group-by first))
           (update-vals (partial into {} (map #(subvec % 1))))))

     (defn count-participants-by-org []
       (->> (dl/q '[:find ?org-name (count ?acc)
                    :in $ %
                    :where
                    [?acc :entity/kind :account]
                    [?m :membership/member ?acc]
                    [?m :membership/entity ?b]
                    [?b :entity/kind :board]
                    [?b :entity/parent ?org]
                    [?org :entity/kind :org]
                    [?org :entity/title ?org-name]
                    (active? ?b)]
                  rules)
            (into {})))

     (defn count-partipicants-by-org-by-year []
       (-> (dl/q '[:find ?org-name ?year (count ?acc)
                   :in $ %
                   :where
                   [?acc :entity/kind :account]
                   [?m :membership/member ?acc]
                   [?m :membership/entity ?b]
                   [?b :entity/kind :board]
                   [?b :entity/parent ?org]
                   [?org :entity/kind :org]
                   [?org :entity/title ?org-name]
                   (created-in-year ?m ?year)
                   (active? ?b)]
                 rules)
           (->> (group-by first))
           (update-vals (partial into {} (map #(subvec % 1))))))

     (defn count-of-kind-per-year [kind]
       (->> (dl/q '[:find ?year (count ?e)
                    :in $ % ?kind
                    :where
                    [?e :entity/kind ?kind]
                    (created-in-year ?e ?year)
                    (active? ?e)]
                  rules
                  kind)
            (into {})))

     (defn count-of-partipicants []
       (dl/q '[:find (count ?acc) .
               :in $ %
               :where
               [?acc :entity/kind :account]
               [?m :membership/member ?acc]
               [?m :membership/entity ?board]
               [?board :entity/kind :board]
               (active? ?board)]
             rules))

     (defn count-of-partipicants-per-year []
       (->> (dl/q '[:find ?year (count ?acc)
                    :in $ %
                    :where
                    [?acc :entity/kind :account]
                    [?m :membership/member ?acc]
                    [?m :membership/entity ?board]
                    [?board :entity/kind :board]
                    (created-in-year ?m ?year)
                    (active? ?board)]
                  rules)
            (into {})))
     ))

(q/defquery show
  ;; TODO proper authorization
  {:prepare az/require-account!}
  [params]
  {:orgs (count-of-kind :org)

   :all {:board (count-of-kind :board)
         :board-year (count-of-kind-per-year :board)
         :project (count-of-kind :project)
         :project-year (count-of-kind-per-year :project)
         :participant (count-of-partipicants)
         :participant-year (count-of-partipicants-per-year)}

   :by-org (u/map-transpose
            {:board (count-boards-by-org)
             :board-year (count-boards-by-org-by-year)
             :project (count-projects-by-org)
             :project-year (count-projects-by-org-by-year)
             :participant (count-participants-by-org)
             :participant-year (count-partipicants-by-org-by-year)})
   })

(comment
   :entities-per-kind
   (dl/q '[:find (count ?e) ?kind
           :where [?e :entity/kind ?kind]])

   :boards-per-org
   (->> (dl/q '[:find ?title (count ?board)
                :where [?e :entity/kind :org]
                [?e :entity/title ?title]
                [?board :entity/parent ?e]])
        (sort-by second >))

   :memberships-per-account
   (->>  (dl/q '[:find ?acc (count ?memb)
                 :where
                 [?memb :membership/member ?acc]
                 [?acc :entity/kind :account]])
         (map second)
         frequencies
         (sort-by first))

   :created-by-year
   (->> (dl/q '[:find [ ?date ...]
                :where
                [_ :entity/created-at ?date]]
              )
        (map #(+ 1900 (.getYear %)))
        frequencies
        (sort-by first))

   :boards-created-by-year
   (->> (dl/q '[:find [ ?date ...]
                :where
                [?board :entity/kind :board]
                [?board :entity/created-at ?date]]
              )
        (map #(+ 1900 (.getYear %)))
        frequencies
        (sort-by first))
  )

(defn stat-type [x]
  (cond (:db/id x)   (:entity/kind (dl/entity (:db/id x)))
        (string? x)  'string
        (map? x)     'map
        (vector? x)  'vector
        (set? x)     'set
        (integer? x) 'integer
        #?@(:clj [(instance? Date x) 'date])
        :else        (str (type x))))

(defn truncate-frequencies [n freqs]
  (let [[head tail] (split-at n (sort-by val > freqs))
        other (apply + (map val tail))]
    (cond-> head
      (< 0 other)
      (concat [[::other other]]
              (apply merge-with + (for [[k v] freqs]
                                    {(stat-type k) v}))))))

(defn frequencies-by-key [ms]
  (->> ms
       (map #(update-vals % (fn [v] {v 1})))
       (apply merge-with (partial merge-with +))))

(q/defquery entity-stats
  ;; TODO proper authorization
  {:prepare az/require-account!}
  [params]
  (-> (dl/q '[:find [(pull ?e [*]) ...]
              :where [?e]])
      (->> (map #(dissoc % :db/id :entity/id))
           (group-by :entity/kind))
      (update-vals (comp #(update-vals % (partial truncate-frequencies 5))
                         frequencies-by-key))))
