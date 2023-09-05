(ns sparkboard.entities.member
  (:require [re-db.api :as db]
            [java-time.api :as time]
            [sparkboard.datalevin :as dl]
            [re-db.api :as db]))

(defn membership-id [entity-id account-id]
  (dl/entid [:member/entity+account [(dl/resolve-id entity-id) (dl/resolve-id account-id)]]))

(defn read-query [params]
  (dissoc (db/pull '[*
                     {:member/tags [*]}]
                   [:entity/id (:member params)])
          :member/password))

(defn read-and-log! 
  ([entity-id account-id]
   (when-let [id (and account-id (membership-id entity-id account-id))]
     (read-and-log! {:member id})))
  ([params]
   (when-let [member (dl/pull '[:db/id 
                                :member/last-visited] 
                              (dl/resolve-id (:member params)))]
     (db/transact! [[:db/add (:db/id member) :member/last-visited 
                     (-> (time/offset-date-time) 
                         time/instant 
                         (java.util.Date/from ))]]))))