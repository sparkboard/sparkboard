(ns sb.authorize
  (:require [clojure.set :as set]
            [re-db.api :as db]
            [sb.server.datalevin :as dl]
            [sb.schema :as sch]))

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
   (defn get-roles [account-id entity-id]
     (let [account-id (dl/resolve-id account-id)
           entity-id  (dl/resolve-id entity-id)]
       (if (= account-id entity-id)
         #{:role/owner}
         (db/get (membership-id account-id entity-id) :member/roles)))))

#?(:clj
   (defn roles-for [account-id entity-id]
     (->> (db/entity entity-id)
          (iterate :entity/parent)
          (take-while identity)
          (mapcat (partial get-roles account-id))
          (into #{}))))

(comment
  (let [account-id [:entity/id #uuid"b03a4669-a7ef-3e8e-bddc-8413e004c338"]
        entity-id [:entity/id #uuid"a4ede6c0-22c2-3902-86ef-c1b8149d0a75"]]
    (roles-for account-id entity-id)))

#?(:clj
   (defn with-roles [entity-key]
     (fn [req params]
       (when-let [account-id (some-> (-> req :account :entity/id) sch/wrap-id)]
         (assoc params :member/roles (roles-for account-id (entity-key params)))))))