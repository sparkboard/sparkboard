(ns sb.authorize
  #?(:cljs (:require-macros [sb.authorize :refer [auth-guard!]]))
  (:require [re-db.api :as db]
            [sb.server.datalevin :as dl]
            [sb.schema :as sch]
            [sb.util :as u])
  #?(:clj (:import [re_db.read Entity])))

(def rules
  '[[(deleted? ?e)
     [?e :entity/deleted-at ?deleted-at]]])

(defn raw-membership
  "Returns membership (posssibly deleted) for the given member and entity"
  [member-id entity-id]
  ;; TODO, use malli for validations like these
  (when-not member-id
    (throw (ex-info "Missing member-id" {:entity-id entity-id})))
  (when-not entity-id
    (throw (ex-info "Missing entity-id" {:member-id member-id})))
  #?(:cljs (first (db/where [[:membership/entity (sch/wrap-id entity-id)]
                             [:membership/member (sch/wrap-id member-id)]]))
     :clj (db/entity [:membership/entity+member [(:db/id (dl/entity (sch/wrap-id entity-id)))
                                                 (:db/id (dl/entity (sch/wrap-id member-id)))]])))

(defn membership [member-id entity-id]
  (-> (raw-membership member-id entity-id)
      (u/guard (complement sch/deleted?))))

(defn deleted-membership [member-id entity-id]
  (-> (raw-membership member-id entity-id)
      (u/guard sch/deleted?)))

(defn membership-id
  "Returns membership id for the given member and entity, or nil if not found."
  [member-id entity-id]
  (:db/id (not-empty (membership member-id entity-id))))

(defn deleted-membership-id
  "Returns deleted membership id for the given member and entity, or nil if not found."
  [member-id entity-id]
  (:db/id (not-empty (deleted-membership member-id entity-id))))

(defn admin-role? [roles]
  (or (:role/project-admin roles)
      (:role/board-admin roles)
      (:role/org-admin roles)))

(defn editor-role? [roles]
  (or (:role/self roles)
      (:role/project-editor roles)
      (admin-role? roles)))

(defn require-account! [req params]
  (when-not (-> req :account :entity/id)
    (throw (ex-info "User not signed in" {:code 400})))
  params)

(defn with-account-id [req params]
  (assoc params :account-id [:entity/id (-> req :account :entity/id)]))

(defn unauthorized! [message & [data]]
  (throw (ex-info message (merge {:code 400} data))))

#?(:clj
   (defmacro auth-guard! [test message & body]
     `(if ~test
        (do ~@body)
        (unauthorized! ~message))))

(defn with-account-id! [req params]
  (let [params (with-account-id req params)]
    (auth-guard! (:account-id params)
        "User not signed in"
      params)))

(defn with-member-id! [entity-fn]
  (fn [req params]
    (let [member-id (membership-id (-> req :account :entity/id)
                                   (entity-fn params))]
      (auth-guard! member-id
          "Not a member"
        (assoc params :member-id member-id)))))

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

(defn all-roles [account-id entity]
  (->> (cons entity
             (u/iterate-some :entity/parent (or (:entity/parent entity)
                                                (:membership/entity entity))))
       (into #{} (mapcat (partial get-roles account-id)))))

(comment

  (let [me (first (db/where [[:account/email "mhuebert@gmail.com"]]))
        memberships (:membership/_member me)]
    (-> memberships first :membership/entity :entity/kind)
    ;; => :board
    (all-roles me (-> memberships first :membership/entity))
    ; #=> #{:role/board-admin :role/org-admin}
    )

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

(defn assert-can-admin-or-self [id-key]
  (fn assert-can-admin-or-self* [req params]
    (let [entity (dl/entity (id-key params))
          account-id (-> req :account :entity/id)
          roles (all-roles account-id entity)]
      (auth-guard! (or (:role/self roles)
                       (admin-role? roles))
          "Not authorized to admin this"))))
