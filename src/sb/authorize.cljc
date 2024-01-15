(ns sb.authorize
  (:require [re-db.api :as db]
            [sb.server.datalevin :as dl]
            [sb.schema :as sch])
  #?(:clj (:import [re_db.read Entity])))

(defn membership-id [member-id entity-id]
  [:entity/id (sch/composite-uuid :membership member-id entity-id)])

(def membership (comp db/entity membership-id))

(defn editor-role? [roles]
  (or (:role/self roles)
      (:role/project-admin roles)
      (:role/project-editor roles)
      (:role/board-admin roles)
      (:role/org-admin roles)))


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

(defn entity? [x]
  (instance? #?(:cljs re-db.read/Entity :clj Entity) x))

(defn get-entity [x]
  (if (entity? x)
    x
    (db/entity (dl/resolve-id x))))

(defn membership-account [membership]
  (let [member (:membership/member membership)]
    (if (= :account (:entity/kind member))
      member
      (:membership/member membership))))

(defn get-roles [account-id entity]
  (or (case (:entity/kind entity)
        :account (when (sch/id= account-id entity) #{:role/self})
        :membership (when (sch/id= account-id (membership-account entity)) #{:role/self})
        (:membership/roles #?(:cljs entity
                              :clj  (membership account-id entity))))
      #{}))

(defn scoped-roles [account-id {:as entity :keys [entity/kind]}]
  (->> (get-roles account-id entity)
       (into #{}
             (map (fn [role]
                    (keyword "role"
                             (str (name kind) "-" (name role))))))))

(defn inherited-roles [account-id entity]
  (->> (:entity/parent entity)
       (iterate :entity/parent)
       (take-while identity)
       (mapcat (partial scoped-roles account-id))
       (into #{})))

(defn all-roles [account-id entity]
  (into (get-roles account-id entity)
        (inherited-roles account-id entity)))

(comment
  (let [account-id [:entity/id #uuid"b03a4669-a7ef-3e8e-bddc-8413e004c338"]
        entity-id  [:entity/id #uuid"a4ede6c0-22c2-3902-86ef-c1b8149d0a75"]]
    (all-roles account-id entity-id)
    (db/touch (db/entity entity-id))))

(defn with-roles* [entity-key req params]
  (if-let [account-id (-> req :account :entity/id)]
    (assoc params :membership/roles (all-roles account-id (get-entity (entity-key params))))
    params))

#?(:clj
   (defn with-roles [entity-key]
     (fn [req params]
       (#'with-roles* entity-key req params))))