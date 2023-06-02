(ns sparkboard.entities.member
  (:require [re-db.api :as db]))

(defn read-query [params]
  (dissoc (db/pull '[*
                     {:member/tags [*]}]
                   [:entity/id (:member params)])
          :member/password))