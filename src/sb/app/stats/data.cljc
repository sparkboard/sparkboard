(ns sb.app.stats.data
  (:require [sb.authorize :as az]
            [sb.query :as q]
            [sb.server.datalevin :as dl]
            [sb.util :as u]
            [re-db.api :as db])
  #?(:clj (:import [java.util Date])))

#?(:clj
   (do
     (defn count-of-kind [kind]
       (dl/q `[:find (count ?e) .
               :where
               [?e :entity/kind ~kind]]))

     (defn count-of-kind-per-year [kind]
       (->> (dl/q `[:find ?year (count ?e)
                    :where
                    [?e :entity/kind ~kind]
                    [?e :entity/created-at ?created-at]
                    [(.getYear ^Date ?created-at) ?year*]
                    [(+ ?year* 1900) ?year]])
            (sort-by first)))

     (defn count-of-partipicants []
       (dl/q `[:find (count ?acc) .
               :where
               [?acc :entity/kind :account]
               [?m :membership/member ?acc]
               [?m :membership/entity ?board]
               [?board :entity/kind :board]]))

     (defn count-of-partipicants-per-year []
       (->> (dl/q `[:find ?year (count ?acc)
                    :where
                    [?acc :entity/kind :account]
                    [?m :membership/member ?acc]
                    [?m :membership/entity ?board]
                    [?board :entity/kind :board]
                    [?m :entity/created-at ?created-at]
                    [(.getYear ^Date ?created-at) ?year*]
                    [(+ ?year* 1900) ?year]])
            (sort-by first)))
     ))

(q/defquery show
  ;; TODO proper authorization
  {:prepare az/require-account!}
  [params]
  {:orgs (count-of-kind :org)
   :boards (count-of-kind :board)
   :projects (count-of-kind :project)
   :participants (count-of-partipicants)

   :boards-created-per-year (count-of-kind-per-year :board)
   :projects-created-per-year (count-of-kind-per-year :project)
   :participants-per-year (count-of-partipicants-per-year)
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
