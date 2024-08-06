(ns sb.app.vote.data
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [sb.app.membership.data :as member.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.validate :as validate]))

(sch/register!
  {:member-vote/open? {:doc "Opens a community vote (shown as a tab on the board)"
                       s-   :boolean}
   :ballot/as-map     {s- [:map {:closed true}
                           :entity/kind
                           :entity/id
                           :ballot/key
                           :ballot/board+account
                           :ballot/board
                           :ballot/project
                           (? :entity/created-at)
                           :entity/created-by
                           ]}
   :ballot/key        (merge {:doc "ballot/board + entity/created-by + ballot/project"}
                             sch/unique-id-str)
   :ballot/board+account (merge {:doc "ballot/board + entity/created-by"}
                                sch/unique-id-str)
   :ballot/board      (sch/ref :one)
   :ballot/project    (sch/ref :one)})

(q/defx new!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id project-id]}]
  (let [board-id (sch/wrap-id (:entity/parent (dl/entity project-id)))
        ballot (-> {
                    :ballot/key           (str/join "+" (map sch/unwrap-id [board-id account-id project-id]))
                    :ballot/board+account (str/join "+" (map sch/unwrap-id [board-id account-id]))
                    :ballot/board board-id
                    :ballot/project project-id}
                   (dl/new-entity :ballot :by account-id))]
    (member.data/ensure-membership! account-id board-id)
    (validate/assert ballot :ballot/as-map)
    (db/transact! [ballot])
    {:entity/id (:entity/id ballot)}))
