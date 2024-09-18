(ns sb.app.membership.data
  (:require [sb.app.entity.data :as entity.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.util :as u]
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

                                             ;; TODO, backfill?
                                             ;; only missing for memberships of projects, orgs and collections
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
  {:prepare az/require-account!}
  [params]
  (dissoc (q/pull `[~@entity.data/listing-fields
                    :entity/tags
                    :entity/field-entries
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
       (not (:entity/deleted-at member))))

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

#?(:clj
   (defn assert-can-edit [id-key]
     (fn assert-can-edit* [req params]
       (let [entity (dl/entity (id-key params))
             account-id (-> req :account :entity/id)]
         (az/auth-guard! (az/editor-role? (az/all-roles account-id entity))
             "Not authorized to edit this")))))

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

(q/defquery search-membership
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id entity-id search-term]}]
  (if entity-id
    ;; scoped to entity
    (assert false "not implemented yet")
    ;; all entities I'm also a member of
    (dl/q '[:find [(pull ?you [:entity/id
                               {:membership/entity [:entity/id {:image/avatar [:entity/id]}]
                                :membership/member
                                ;; TODO could use entity.data/listing-fields but quasiqouting is awkward here
                                [:account/display-name
                                 :entity/id
                                 {:image/avatar [:entity/id]}]}])
                   ...]
            :in $ ?my-account ?search-term
            :where
            [?me :membership/member ?my-account]
            [?me :membership/entity ?entity]
            [?you :membership/entity ?entity]
            [?you :membership/member ?your-account]
            [(fulltext $ ?search-term {:top 20}) [[?your-account ?a ?v]]]]
          account-id
          search-term)))

(q/defquery search
  {:prepare [az/with-account-id!]}
  [{:as params :keys [account-id entity-id search-term]}]
  (if entity-id
    ;; scoped to entity
    (do
      (ensure-membership! account-id entity-id)
      (dl/q '[:find [(pull ?account [:account/display-name
                                     :entity/id
                                     {:image/avatar [:entity/id]}]) ...]
              :in $ ?entity ?search-term
              :where
              [?m :membership/entity ?entity]
              [?m :membership/member ?account]
              [(fulltext $ ?search-term {:top 20}) [[?account ?a ?v]]]]
            entity-id
            search-term))
    ;; all entities I'm also a member of
    (dl/q '[:find [(pull ?your-account [:account/display-name
                                        :entity/id
                                        {:image/avatar [:entity/id]}]) ...]
            :in $ ?my-account ?search-term
            :where
            [?me :membership/member ?my-account]
            [?me :membership/entity ?entity]
            [?you :membership/entity ?entity]
            [?you :membership/member ?your-account]
            [(fulltext $ ?search-term {:top 20}) [[?your-account ?a ?v]]]]
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

(defn new-entity-with-membership [entity member-id roles]
  {:entity/id         (random-uuid)
   :entity/kind       :membership
   :membership/member (sch/wrap-id member-id)
   :membership/entity entity
   :membership/roles  roles})

(defn resolved-tags [board-membership]
  (mapv (comp (u/index-by (:entity/member-tags (:membership/entity board-membership))
                          :tag/id)
              :tag/id)
        (:entity/tags board-membership)))

(defn membership-colors [membership]
  (mapv :tag/color (resolved-tags membership)))
