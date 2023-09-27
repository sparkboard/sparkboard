(ns sparkboard.endpoints
  (:require [java-time.api :as time]
            [re-db.api :as db]
            [sparkboard.datalevin :as dl])
  (:import [java.util Date]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Members

(defn membership-id [entity-id account-id]
  (dl/entid [:member/entity+account [(dl/resolve-id entity-id) (dl/resolve-id account-id)]]))

(defn member:read-and-log!
  ([entity-id account-id]
   (when-let [id (and account-id (membership-id entity-id account-id))]
     (member:read-and-log! {:member id})))
  ([params]
   (when-let [member (dl/pull '[:db/id
                                :member/last-visited]
                              (dl/resolve-id (:member params)))]
     (db/transact! [[:db/add (:db/id member) :member/last-visited
                     (-> (time/offset-date-time)
                         time/instant
                         Date/from)]]))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Orgs




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Pull

(defn pull
  {:endpoint {:query ["/pull"]}}
  ;; TODO
  ;; generic, rule-based auth?
  [{:keys [id expr]}]
  (db/pull expr id))
