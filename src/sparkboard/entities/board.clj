(ns sparkboard.entities.board
  (:require [clojure.set :as set]
            [malli.util :as mu]
            [re-db.api :as db]
            [sparkboard.datalevin :as dl]
            [sparkboard.entities.domain :as domain]
            [sparkboard.entities.entity :as entity]
            [sparkboard.validate :as validate]
            [sparkboard.entities.member :as member]))

(defn register! [req params registration-data]
  ;; create membership
  )

(defn read-query
  {:authorize (fn [req params]
                (member/read-and-log! (:board params) (:db/id (:account req))))}
  [params]
  (db/pull `[~@entity/fields
             :board/registration-open?
             {:board/owner [~@entity/fields
                            :org/show-org-tab?]}
             {:project/_board ~entity/fields}
             {:member/_entity [~@entity/fields
                               {:member/account ~entity/account-as-entity-fields}]}]
           [:entity/id (:board params)]))

(defn authorize-edit! [board account-id]
  (validate/assert board [:map [:board/owner
                                [:fn {:error/message "Not authorized."}
                                 (fn [owner]
                                   (let [owner-id (:db/id (dl/entity owner))]
                                     (or
                                       ;; board is owned by this account
                                       (= account-id owner-id)
                                       ;; account is editor of this board (existing)
                                       (when-let [existing-board (dl/entity [:entity/id (:entity/id board)])]
                                         (entity/can-edit? (:db/id existing-board) account-id))
                                       ;; account is admin of board's org
                                       (entity/can-edit? owner-id account-id))))]]]))

(defn edit! [{:keys [account]} params board]
  (let [board (entity/conform (assoc board :entity/id (:board params)) :board/as-map)]
    (authorize-edit! board (:db/id account))
    (db/transact! [board])
    {:body board}))



(defn new!
  [{:keys [account]} _ board]
  ;; TODO
  ;; confirm that owner is account, or account is admin of org
  (let [board  (-> (dl/new-entity board :board :by (:db/id account))
                   (entity/conform :board/as-map))
        _      (authorize-edit! board (:db/id account))
        member (-> {:member/entity  board
                    :member/account (:db/id account)
                    :member/roles   #{:role/admin}}
                   (dl/new-entity :member))]
    (db/transact! [member])
    {:body board}))