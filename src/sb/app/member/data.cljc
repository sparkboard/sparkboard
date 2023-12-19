(ns sb.app.member.data
  (:require #?(:clj [java-time.api :as time])
            [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl])
  #?(:clj (:import [java.util Date])))

(sch/register!
  {:roles/as-map                    {s- :member/as-map}
   :member/last-visited             (merge sch/instant
                                           {s- 'inst?})
   :member/entity+account           (merge {:db/tupleAttrs [:member/entity :member/account]}
                                           sch/unique-value)
   :member/_entity                  {s- [:or
                                         :member/as-map
                                         [:sequential
                                          :member/as-map]]}
   :member/role                     {s- [:enum
                                         :role/admin
                                         :role/owner
                                         :role/collaborate
                                         :role/member]}
   :member/roles                    (merge sch/keyword
                                           sch/many
                                           {s- [:set :member/role]})
   :member/entity                   (sch/ref :one)
   :member/account                  (sch/ref :one)

   :member/tags                     (sch/ref :many :tag/as-map)
   :member/ad-hoc-tags              {s- [:sequential [:map {:closed true} :tag/label]]}

   ;; TODO: move/remove. this should simply be a field that an organizer adds.
   :member/newsletter-subscription? {s- :boolean},

   ;; TODO: move email-frequency to a separate place (focused on notifications)
   :member/email-frequency          {s- [:enum
                                         :member.email-frequency/never
                                         :member.email-frequency/daily
                                         :member.email-frequency/periodic
                                         :member.email-frequency/instant]}


   ;; TODO: better define this state.
   ;; - used for people who are no longer attending / need to be managed by organizers.
   ;; - different than deleted - user can still sign in to reactivate?
   :member/inactive?                {:doc   "Marks a member inactive, hidden."
                                     :admin true
                                     :todo  "If an inactive member signs in to a board, mark as active again?"
                                     s-     :boolean},


   :member/as-map                   {s- [:map {:closed true}
                                         :entity/id
                                         :entity/kind
                                         :member/entity
                                         :member/account

                                         (? :member/inactive?)
                                         (? :member/email-frequency)
                                         (? :member/ad-hoc-tags)
                                         (? :member/newsletter-subscription?)
                                         (? :member/tags)
                                         (? :member/roles)
                                         (? :member/last-visited)

                                         ;; TODO, backfill?
                                         (? :entity/created-at)
                                         (? :entity/updated-at)

                                         (? :entity/field-entries)
                                         (? :entity/deleted-at)
                                         (? :entity/modified-by)]}})

(q/defquery show
  {:prepare az/require-account!}
  [params]
  (dissoc (q/pull `[{:member/tags [:*]}
                    {:member/account [~@entity.data/fields
                                      :account/display-name]}]
                  (:member-id params))
          :member/password))

#?(:clj
   (defn membership-id [account-id entity-id]
     (dl/entid [:member/entity+account [(dl/resolve-id entity-id) (dl/resolve-id account-id)]])))

#?(:clj
   (defn ensure-membership! [account-id entity-id]
     (when-not (membership-id account-id entity-id)
       (throw (ex-info "Not a member" {:status 403})))))

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
              [?m :member/entity ?entity]
              [?m :member/account ?account]
              [(fulltext $ ?search-term {:top 20}) [[?account ?a ?v]]]]
            entity-id
            search-term))
    ;; all entities I'm also a member of
    (dl/q '[:find [(pull ?your-account [:account/display-name
                                        :entity/id
                                        {:image/avatar [:entity/id]}]) ...]
            :in $ ?my-account ?search-term
            :where
            [?me :member/account ?my-account]
            [?me :member/entity ?entity]
            [?you :member/entity ?entity]
            [?you :member/account ?your-account]
            [(fulltext $ ?search-term {:top 20}) [[?your-account ?a ?v]]]]
          account-id
          search-term)))

(comment
  (search {:account-id     [:entity/id #uuid "b08f39bf-4f31-3d0b-87a6-ef6a2f702d30"]
              ;:entity-id   [:entity/id #uuid "a1630339-64b3-3604-8110-0f22355e12be"]
              :search-term "matt"}))

#?(:clj
   (defn member:log-visit! [entity-key]
     (fn [req params]
       (when-let [id (some-> (-> req :account :entity/id)
                             (membership-id (entity-key params)))]
         (db/transact! [[:db/add id :member/last-visited
                         (-> (time/offset-date-time)
                             time/instant
                             Date/from)]]))
       params)))

(defn new-entity-with-membership [entity account-id roles]
  {:entity/id      (random-uuid)
   :entity/kind    :member
   :member/account (sch/wrap-id account-id)
   :member/entity  entity
   :member/roles   roles})