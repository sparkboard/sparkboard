(ns sparkboard.entities.project
  (:require [re-db.api :as db]
            [sparkboard.datalevin :as dl]
            [sparkboard.entities.entity :as entity]
            [sparkboard.entities.member :as member]
            [sparkboard.validate :as validate]))

(defn read-query 
  {:authorize (fn [req {:as params :keys [project-id]}]
                (member/read-and-log! project-id (:db/id (:account req)))
                params)}
  [{:keys [project-id]}]
  (db/pull `[~@entity/fields :project/sticky?] [:entity/id project-id]))

(defn new! [req {:as params project :body}]
  (validate/assert project [:map {:closed true} :entity/title])
  ;; auth: user is member of board & board allows members to create projects
  (db/transact! [(-> project
                     (assoc :project/board [:entity/id (:entity/id params)])
                     (dl/new-entity :project :by (:db/id (:account req))))])
  ;; what to return?
  {:status 201}
  )
