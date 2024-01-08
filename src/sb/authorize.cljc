(ns sb.authorize
  (:require [clojure.set :as set]
            [re-db.api :as db]
            [sb.server.datalevin :as dl]
            [sb.schema :as sch])
  #?(:clj (:import [re_db.read Entity])))

(defn editor-role? [roles]
  (or (:role/self roles)
      (:role/project-admin roles)
      (:role/project-editor roles)
      (:role/board-admin roles)
      (:role/org-admin roles)))

(defn membership-id [account-id entity-id]
  #?(:clj
     (dl/entid [:member/entity+account [(dl/resolve-id entity-id) (dl/resolve-id account-id)]])
     :cljs
     (dl/resolve-id entity-id)))

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

(defn get-entity [x]
  (if (instance? #?(:cljs re-db.read/Entity :clj Entity) x)
    x
    (db/entity (dl/resolve-id x))))

(defn get-roles [account-id entity-id]
  (:member/roles (db/entity (membership-id account-id entity-id))))

(defn scoped-roles [account-id entity-id]
  (if (= account-id entity-id)
    #{:role/self}
    (let [{:as entity :keys [entity/kind]} (get-entity entity-id)]
      (->> (:member/roles #?(:cljs entity
                             :clj  (db/entity (membership-id account-id entity-id))))
           (into #{}
                 (map (fn [role]
                        (keyword "role"
                                 (str (name kind) "-" (name role))))))))))

(defn inherited-roles [account-id entity-id]
  (->> (:entity/parent (get-entity entity-id))
       (iterate :entity/parent)
       (take-while identity)
       (mapcat (partial scoped-roles account-id))
       (into #{})))

(defn all-roles [account-id entity-id]
  (into (get-roles account-id entity-id)
        (inherited-roles account-id entity-id)))

(def can-edit? (comp editor-role? all-roles))

(comment
  (let [account-id [:entity/id #uuid"b03a4669-a7ef-3e8e-bddc-8413e004c338"]
        entity-id  [:entity/id #uuid"a4ede6c0-22c2-3902-86ef-c1b8149d0a75"]]
    (all-roles account-id entity-id)
    (db/touch (db/entity entity-id))))

#?(:clj
   (defn with-roles [entity-key]
     (fn [req params]
       (if-let [account-id (some-> (-> req :account :entity/id) sch/wrap-id)]
         (assoc params :member/roles (get-roles account-id (entity-key params)))
         params))))