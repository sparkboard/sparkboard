(ns sparkboard.entities.board
  (:require [re-db.api :as db]
            [sparkboard.datalevin :as sd]
            [sparkboard.entities.entity :as entity]
            [sparkboard.validate :as validate]))

(defn new! [req params board]
  (validate/assert board [:map {:closed true} :entity/title])
  ;; auth: user is admin of :board/org
  (db/transact!
    [(-> board
         (assoc :board/org [:entity/id (:entity/id params)])
         (sd/new-entity :board :by (:db/id (:account req))))])
  (db/pull '[*]))

(defn register! [req params registration-data]
  ;; create membership
  )

(defn read-query [params]
  (db/pull `[~@entity/fields
             :board/registration-open?
             {:board/org [~@entity/fields
                          :org/show-org-tab?]}

             {:project/_board ~entity/fields}
             {:member/_board ~entity/fields}]
           [:entity/id (:board params)]))
