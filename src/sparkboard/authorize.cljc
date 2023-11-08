(ns sparkboard.authorize
  (:require [re-db.api :as db]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.schema :as sch]))

(defn editor-role? [roles]
  (or (:role/admin roles)
      (:role/collaborate roles)))

#?(:clj
   (defn membership-id [account-id entity-id]
     (dl/entid [:member/entity+account [(dl/resolve-id entity-id) (dl/resolve-id account-id)]])))

(defn require-account! [req params]
  (when-not (-> req :account :entity/id)
    (throw (ex-info "User not signed in" {:code 400})))
  params)

(defn with-account-id [req params]
  (assoc params :account-id [:entity/id (-> req :account :entity/id)]))

(defn unauthorized! [message & [data]]
  (throw (ex-info message (merge {:code 400} data))))

(defn with-account-id! [req params]
  (let [params (with-account-id req params)]
    (when-not (:account-id params)
      (unauthorized! "User not signed in"))
    params))

#?(:clj
   (defn with-roles [entity-key]
     (fn [req params]
       (assoc params :member/roles (db/get (membership-id (some-> (-> req :account :entity/id)
                                                                  sch/wrap-id)
                                                          (entity-key params))
                                           :member/roles)))))