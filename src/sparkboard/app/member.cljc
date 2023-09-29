(ns sparkboard.app.member
  (:require #?(:clj [java-time.api :as time])
            #?(:clj [sparkboard.server.datalevin :as dl])
            [sparkboard.i18n :refer [tr]]
            [sparkboard.entity :as entity]
            [sparkboard.schema :as sch :refer [s- ?]]
            [sparkboard.ui :as ui]
            [sparkboard.websockets :as ws]
            [re-db.api :as db])
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
                                         :role/collaborator
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

#?(:clj
   (defn db:read
     {:endpoint {:query ["/m/" ['uuid :member-id]]}}
     [params]
     (dissoc (db/pull `[:*
                        {:member/tags [:*]}
                        {:member/account [~@entity/fields
                                          :account/display-name]}]
                      [:entity/id (:member-id params)])
             :member/password)))

(ui/defview read
  {:view/target :modal
   :endpoint {:view ["/m/" ['uuid :member-id]]}}
  [params]
  (let [{:as          member
         :member/keys [tags
                       ad-hoc-tags
                       account]} (ws/use-query! [`db:read params])
        {:keys [:account/display-name
                :image/avatar]} account]
    [:div
     [:h1 display-name]
     ;; avatar
     ;; fields
     (when-let [tags (seq (concat tags ad-hoc-tags))]
       [:section [:h3 (tr :tr/tags)]
        (into [:ul]
              (map (fn [{:tag/keys [label background-color]}]
                     [:li {:style (when background-color {:background-color background-color})} label]))
              tags)])
     (when avatar [:img {:src (ui/asset-src avatar :card)}])]))

#?(:clj
   (defn membership-id [entity-id account-id]
     (dl/entid [:member/entity+account [(dl/resolve-id entity-id) (dl/resolve-id account-id)]])))

#?(:clj
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
                            Date/from)]])))))