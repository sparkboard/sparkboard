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
          [(+ ?year* 1900) ?year]]])

     (defn count-of-kind [kind]
       (dl/q `[:find (count ?e) .
               :where
               [?e :entity/kind ~kind]]))

     (defn count-boards-by-org []
       (->> (dl/q '[:find ?org-name (count ?b)
                    :where
                    [?b :entity/kind :board]
                    [?b :entity/parent ?org]
                    [?org :entity/kind :org]
                    [?org :entity/title ?org-name]])
            (into {})))

     (defn count-boards-by-org-by-year []
       (-> (dl/q '[:find ?org-name ?year (count ?b)
                    :in $ %
                    :where
                    [?b :entity/kind :board]
                    [?b :entity/parent ?org]
                    [?org :entity/kind :org]
                    [?org :entity/title ?org-name]
                    (created-in-year ?b ?year)]
                  rules)
           (->> (group-by first))
           (update-vals (partial into {} (map #(subvec % 1))))))

     (defn count-projects-by-org []
       (->> (dl/q '[:find ?org-name (count ?p)
                    :where
                    [?p :entity/kind :project]
                    [?p :entity/parent ?b]
                    [?b :entity/kind :board]
                    [?b :entity/parent ?org]
                    [?org :entity/kind :org]
                    [?org :entity/title ?org-name]])
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
                   (created-in-year ?p ?year)]
                 rules)
           (->> (group-by first))
           (update-vals (partial into {} (map #(subvec % 1))))))

     (defn count-participants-by-org []
       (->> (dl/q `[:find ?org-name (count ?acc)
                    :where
                    [?acc :entity/kind :account]
                    [?m :membership/member ?acc]
                    [?m :membership/entity ?b]
                    [?b :entity/kind :board]
                    [?b :entity/parent ?org]
                    [?org :entity/kind :org]
                    [?org :entity/title ?org-name]])
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
                   (created-in-year ?m ?year)]
                 rules)
           (->> (group-by first))
           (update-vals (partial into {} (map #(subvec % 1))))))

     (defn count-of-kind-per-year [kind]
       (->> (dl/q '[:find ?year (count ?e)
                    :in $ % ?kind
                    :where
                    [?e :entity/kind ?kind]
                    (created-in-year ?e ?year)]
                  rules
                  kind)
            (into {})))

     (defn count-of-partipicants []
       (dl/q `[:find (count ?acc) .
               :where
               [?acc :entity/kind :account]
               [?m :membership/member ?acc]
               [?m :membership/entity ?board]
               [?board :entity/kind :board]]))

     (defn count-of-partipicants-per-year []
       (->> (dl/q '[:find ?year (count ?acc)
                    :in $ %
                    :where
                    [?acc :entity/kind :account]
                    [?m :membership/member ?acc]
                    [?m :membership/entity ?board]
                    [?board :entity/kind :board]
                    (created-in-year ?m ?year)]
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
