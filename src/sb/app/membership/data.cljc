(ns sb.app.membership.data
  (:require [sb.app.entity.data :as entity.data]
            [sb.app.notification.data :as notification.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.util :as u]
            [sb.validate :as validate]
            [re-db.api :as db])
  #?(:clj (:import [java.util Date])))

(sch/register!
  {:roles/as-map                        {s- :membership/as-map}
   :membership/entity+member            (merge {:db/tupleAttrs [:membership/entity :membership/member]}
                                               sch/unique-value)
   :membership/_entity                  {s- [:or
                                             :membership/as-map
                                             [:sequential
                                              :membership/as-map]]}
   :membership/role                     {s- [:enum
                                             :role/board-admin
                                             :role/org-admin
                                             :role/project-admin
                                             :role/project-editor
                                             :role/project-member]}
   :membership/roles                    {s- [:set :membership/role]}
   :membership/entity                   (sch/ref :one)
   :membership/member                   (sch/ref :one)
   :membership/member-approval-pending? {s- :boolean}

   :entity/tags                         {s- [:sequential [:map {:closed true} :tag/id]]}
   :entity/custom-tags                  {s- [:sequential [:map {:closed true} :tag/label]]}

   ;; TODO: move/remove. this should simply be a field that an organizer adds.
   :membership/newsletter-subscription? {s- :boolean},

   ;; TODO: move email-frequency to a separate place (focused on notifications)
   :membership/email-frequency          {s- [:enum
                                             :member.email-frequency/never
                                             :member.email-frequency/daily
                                             :member.email-frequency/periodic
                                             :member.email-frequency/instant]}


   ;; TODO: better define this state.
   ;; - used for people who are no longer attending / need to be managed by organizers.
   ;; - different than deleted - user can still sign in to reactivate?
   :membership/inactive?                {:doc   "Marks a member inactive, hidden."
                                         :admin true
                                         :todo  "If an inactive member signs in to a board, mark as active again?"
                                         s-     :boolean},


   :membership/as-map                   {s- [:map {:closed true}
                                             :entity/id
                                             :entity/kind
                                             :membership/entity
                                             :membership/member
                                             (? :entity/uploads)
                                             (? :membership/inactive?)
                                             (? :membership/email-frequency)
                                             (? :entity/custom-tags)
                                             (? :membership/newsletter-subscription?)
                                             (? :entity/tags)
                                             (? :membership/roles)
                                             (? :membership/member-approval-pending?)

                                             ;; TODO, backfill?
                                             ;; only missing for memberships of orgs and collections
                                             ;; not for board memberships
                                             (? :entity/created-at)
                                             (? :entity/updated-at)

                                             (? :entity/field-entries)
                                             (? :entity/deleted-at)
                                             (? :entity/modified-by)]}})

(comment
  ;; Stats for memberships which do and do not have `:entity/created-at`
  [(dl/q '[:find ?kind (count ?e)
           :where
           [?e :entity/kind :membership]
           [?e :entity/created-at]
           [?e :membership/entity ?p]
           [?p :entity/kind ?kind]])
   (dl/q '[:find ?kind (count ?e)
           :where
           [?e :entity/kind :membership]
           (not [?e :entity/created-at])
           [?e :membership/entity ?p]
           [?p :entity/kind ?kind]])]
  )

(q/defquery show
  {:endpoint/public? true ;; TODO board visibility
   }
  [params]
  (dissoc (q/pull `[~@entity.data/listing-fields
                    :entity/tags
                    :entity/field-entries
                    :membership/member-approval-pending?
                    {:membership/entity [~@entity.data/id-fields
                                         :entity/member-tags
                                         :entity/member-fields]}
                    {:membership/member ~entity.data/listing-fields}]
                  (:membership-id params))))

#?(:clj
   (defn ensure-membership! [account entity]
     (let [entity (dl/entity entity)]
       (case (:entity/kind entity)
         :project (ensure-membership! account (:entity/parent entity))
         (when-not (az/membership-id account entity)
           (throw (ex-info "Not a member" {:status 403})))))))

(defn active-member? [member]
  (and (not (:membership/inactive? member))
       (not (sch/deleted? member))))

#?(:clj
   (defn can-view? [account-id entity]
     (let [kind (:entity/kind entity)]
       (case kind
         (:board :org) (or (:entity/public? entity)
                           (active-member? (az/membership account-id entity)))
         :project (can-view? account-id (:entity/parent entity))
         :membership (let [member (:membership/member entity)]
                       (case (:entity/kind member)
                         :account (sch/id= account-id member)
                         :membership (can-view? account-id (:membership/entity entity))))))))

#?(:clj
   (defn assert-can-view [id-key]
     (fn assert-can-view* [req params]
       (let [entity (dl/entity (id-key params))
             account-id (-> req :account :entity/id)]
         (when-not (can-view? account-id entity)
           (az/unauthorized! (str "Not authorized to view this "
                                  (:entity/kind entity "entity."))))))))

(defn assert-can-edit [id-key]
  (fn assert-can-edit* [req params]
    (let [entity (dl/entity (id-key params))
          account-id (-> req :account :entity/id)]
      (az/auth-guard! (az/editor-role? (az/all-roles account-id entity))
          "Not authorized to edit this"))))

(q/defquery descriptions
  {:prepare  az/with-account-id!}
  [{:as params :keys [account-id ids]}]
  (u/timed `descriptions
           (into []
                 (comp (map (comp db/entity sch/wrap-id))
                       (filter active-member?)
                       (map (db/pull `[~@entity.data/listing-fields
                                       :entity/public?])))
                 ids)))

(q/defquery search-membership-account
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id entity-id search-term]}]
  (if entity-id
    ;; scoped to entity
    (assert false "not implemented yet")
    ;; all entities I'm also a member of
    (dl/q (u/template
           `[:find [(pull ?your-account ~entity.data/listing-fields)
                    ...]
             :in $ ?my-account ?search-term
             :where
             [?me :membership/member ?my-account]
             [?me :membership/entity ?entity]
             [?you :membership/entity ?entity]
             [?you :membership/member ?your-account]
             [(fulltext $ ?search-term {:top 20}) [[?your-account ?a ?v]]]])
          account-id
          search-term)))

(q/defquery search-membership
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id entity-id search-term]}]
  (if entity-id
    ;; scoped to entity
    (assert false "not implemented yet")
    ;; all entities I'm also a member of
    (dl/q (u/template
           `[:find [(pull ?you [:entity/id
                                {:membership/entity [:entity/id {:image/avatar [:entity/id]}]
                                 :membership/member ~entity.data/listing-fields}])
                    ...]
             :in $ ?my-account ?search-term
             :where
             [?me :membership/member ?my-account]
             [?me :membership/entity ?entity]
             [?you :membership/entity ?entity]
             [?you :membership/member ?your-account]
             [(fulltext $ ?search-term {:top 20}) [[?your-account ?a ?v]]]])
          account-id
          search-term)))

(q/defquery search
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id entity-id search-term]}]
  (if entity-id
    ;; scoped to entity
    (do
      (ensure-membership! account-id entity-id)
      (dl/q (u/template
             `[:find [(pull ?account ~entity.data/listing-fields) ...]
               :in $ ?entity ?search-term
               :where
               [?m :membership/entity ?entity]
               [?m :membership/member ?account]
               [(fulltext $ ?search-term {:top 20}) [[?account ?a ?v]]]])
            entity-id
            search-term))
    ;; all entities I'm also a member of
    (dl/q (u/template
           `[:find [(pull ?your-account ~entity.data/listing-fields) ...]
             :in $ ?my-account ?search-term
             :where
             [?me :membership/member ?my-account]
             [?me :membership/entity ?entity]
             [?you :membership/entity ?entity]
             [?you :membership/member ?your-account]
             [(fulltext $ ?search-term {:top 20}) [[?your-account ?a ?v]]]])
          account-id
          search-term)))

(comment
  ;; does not work
  (q/defquery my-membership
    {:prepare az/with-account-id!}
    [{:keys [account-id entity-id]}]
    {:entity/id        (sch/unwrap-id entity-id)
     :membership/roles (az/all-roles account-id entity-id)}))
(comment
  (search {:account-id  [:entity/id #uuid "b08f39bf-4f31-3d0b-87a6-ef6a2f702d30"]
           ;:entity-id   [:entity/id #uuid "a1630339-64b3-3604-8110-0f22355e12be"]
           :search-term "matt"}))

#?(:clj
   (defn new-entity-with-membership [entity member-id roles]
     {:entity/id         (random-uuid)
      :entity/kind       :membership
      :entity/created-at (java.util.Date.)
      :membership/member (sch/wrap-id member-id)
      :membership/entity entity
      :membership/roles  roles}))

(defn resolved-tags [board-membership]
  (mapv (comp (u/index-by (:entity/member-tags (:membership/entity board-membership))
                          :tag/id)
              :tag/id)
        (:entity/tags board-membership)))

(defn membership-colors [membership]
  (mapv :tag/color (resolved-tags membership)))

(defn raw-memberships
  "Returns memberships (possibly deleted or pending) of `entity`, with an transducer `xform` applied to them."
  ([entity xform]
   (->> (:membership/_entity entity)
        (into [] xform))))

(defn memberships
  "Returns memberships of `entity`, with an optional transducer `xform` applied to them."
  ([entity] (memberships entity identity))
  ([entity xform]
   (raw-memberships entity (comp (remove (some-fn sch/deleted? :membership/member-approval-pending?))
                                 xform))))

(defn pending-memberships
  "Returns pending memberships of `entity`, with an optional transducer `xform` applied to them."
  ([entity] (memberships entity identity))
  ([entity xform]
   (raw-memberships entity (comp (filter (every-pred (complement sch/deleted?) :membership/member-approval-pending?))
                                 xform))))

(defn members
  "Returns members of `entity`, with an optional transducer `xform` applied to the memberships first."
  ([entity] (members entity identity))
  ([entity xform]
   (memberships entity (comp xform (map :membership/member)))))

(defn member-of
  "Returns all entities that `member` is a member of, with an optional transducer `xform` applied to the memberships first."
  ([member] (member-of member identity))
  ([member xform]
   (->> (:membership/_member member)
        (into [] (comp (remove (some-fn sch/deleted? :membership/member-approval-pending?))
                       xform
                       (map :membership/entity))))))

(q/defx join!
  {:prepare [az/with-account-id!
             (fn [_ {:keys [board-id]}]
               (az/auth-guard! (= :admission-policy/open (:entity/admission-policy (dl/entity board-id)))
                   "Board is invite only"))]}
  [{:keys [account-id board-id]}]
  (if-let [board-member-id (az/deleted-membership-id account-id board-id)]
    (do
      (db/transact! [{:db/id board-member-id
                      :membership/member-approval-pending? true
                      :entity/created-at (java.util.Date.)
                      :entity/deleted-at sch/DELETED_SENTINEL}])
      {:entity/id (:entity/id (db/entity board-member-id))})
    (let [membership (-> (new-entity-with-membership (sch/wrap-id board-id)
                                                     account-id
                                                     #{})
                         (assoc :membership/member-approval-pending? true)
                         (validate/assert :membership/as-map))]
      (db/transact! [membership])
      {:entity/id (:entity/id membership)})))

(q/defx join-board-child!
  "Creates or resurects project or note membership"
  {:prepare [az/with-account-id!
             (az/with-member-id! (comp :entity/parent dl/entity :entity-id))
             (fn [_ {:keys [entity-id]}]
               (az/auth-guard! (= :admission-policy/open (:entity/admission-policy (dl/entity entity-id)))
                   "Entity is invite only"))]}
  [{:keys [account-id entity-id]}]
  (let [admins (->> (db/where [[:membership/entity (sch/wrap-id entity-id)]
                               (comp :role/project-admin :membership/roles)])
                    (map (comp sch/wrap-id :membership/member)))
        membership (if-let [entity-member-id (az/deleted-membership-id account-id entity-id)]
                     {:db/id entity-member-id
                      :entity/created-at (java.util.Date.)
                      :entity/deleted-at sch/DELETED_SENTINEL}
                     (-> (new-entity-with-membership (sch/wrap-id entity-id)
                                                     account-id
                                                     #{})
                         (validate/assert :membership/as-map)))]
    (db/transact! [membership
                   (notification.data/new :notification.type/new-member
                                          (or (:db/id membership) (sch/wrap-id membership))
                                          admins)]))
  {:body ""})

(q/defx create-board-child-invitation!
  "Creates or resurects pending project or note membership"
  {:prepare [az/with-account-id!
             (az/assert-can-admin-or-self :entity-id)]}
  [{:keys [invitee-account-id entity-id]}]
  (let [membership (if-let [entity-member-id (az/deleted-membership-id invitee-account-id entity-id)]
                     {:db/id entity-member-id
                      :entity/created-at (java.util.Date.)
                      :entity/deleted-at sch/DELETED_SENTINEL
                      :membership/member-approval-pending? true}
                     (-> (new-entity-with-membership (sch/wrap-id entity-id)
                                                     invitee-account-id
                                                     #{})
                         (assoc :membership/member-approval-pending? true)
                         (validate/assert :membership/as-map)))]
    (db/transact! [membership
                   (notification.data/new :notification.type/new-invitation
                                          (or (:db/id membership) (sch/wrap-id membership))
                                          [(sch/wrap-id invitee-account-id)])]))
  {:body ""})

(q/defx approve-board-membership!
  {:prepare [az/with-account-id!
             (az/with-member-id! :board-id)
             (fn [_ {:keys [board-id member-id] :as params}]
               (let [required-fields? (-> (apply disj (->> (:entity/member-fields (dl/entity board-id))
                                                           (filter :field/required?)
                                                           (map :field/id)
                                                           set)
                                                 (-> (db/entity member-id)
                                                     :entity/field-entries
                                                     keys))
                                          empty?)]
                 (when-not required-fields?
                   (validate/permission-denied! "Not all required fields are filled out")))
               params)]}
  [{:keys [account-id board-id member-id]}]
  (let [admins (->> (db/where [[:membership/entity (sch/wrap-id board-id)]
                               (comp :role/project-admin :membership/roles)])
                    (map (comp sch/wrap-id :membership/member)))]
    (db/transact! [{:db/id member-id
                    :membership/member-approval-pending? false}
                   (notification.data/new :notification.type/new-member
                                          member-id
                                          admins)])
    {:body ""}))

(q/defx approve-membership!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id entity-id]}]
  (if-let [membership-id (az/membership-id account-id entity-id)]
    (let [admins (->> (db/where [[:membership/entity (sch/wrap-id entity-id)]
                                 (comp :role/board-admin :membership/roles)])
                      (map (comp sch/wrap-id :membership/member)))]
      (db/transact! [{:db/id membership-id
                      :membership/member-approval-pending? false}
                     (notification.data/new :notification.type/new-member
                                            membership-id
                                            admins)])
      {:body ""})
    (throw (ex-info "No membership to approve" {}))))
