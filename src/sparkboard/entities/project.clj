(ns sparkboard.entities.project
  (:require [re-db.api :as db]
            [sparkboard.datalevin :as dl]
            [sparkboard.entities.entity :as entity]
            [sparkboard.validate :as validate]))

(defn read-query [params]
  (db/pull `[~@entity/fields] [:entity/id (:project params)]))

(defn new! [req params project]
  (validate/assert project [:map {:closed true} :entity/title])
  ;; auth: user is member of board & board allows members to create projects
  (db/transact! [(-> project
                     (assoc :project/board [:entity/id (:entity/id params)])
                     (dl/new-entity :project :by (:db/id (:account req))))])
  ;; what to return?
  {:status 201}
  )
