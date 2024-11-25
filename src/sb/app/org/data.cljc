(ns sb.app.org.data
  (:require [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
            [sb.app.membership.data :as member.data]
            [sb.app.notification.data :as notification.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.util :as u]
            [sb.validate :as validate]
            [net.cgrand.xforms :as xf]))

(sch/register!
  {:org/show-org-tab?          {:doc "Boards should visibly link to this parent organization"
                                s-   :boolean}
   :entity/id                  sch/unique-uuid
   :org/default-board-template (merge {:doc "Default template (a board with :board/is-template? true) for new boards created within this org"}
                                      (sch/ref :one))
   :org/as-map                 {s- [:map {:closed true}
                                    :entity/id
                                    :entity/title
                                    :entity/kind
                                    :entity/created-by
                                    (? :entity/uploads)
                                    (? :image/avatar)
                                    (? :image/background)
                                    (? :image/sub-header)
                                    (? :org/show-org-tab?)
                                    (? :entity/description)
                                    (? :entity/social-feed)
                                    (? :entity/locale-default)
                                    (? :entity/locale-suggestions)
                                    (? :entity/domain-name)
                                    (? :entity/public?)
                                    (? :org/default-board-template)
                                    (? :entity/created-at)]}})

(q/defx delete!
  "Mutation fn. Retracts organization by given org-id."
  {:endpoint {:post "/o/:org-id/delete"}}
  [_req {:keys [org-id]}]
  ;; auth: user is admin of org
  ;; todo: retract org and all its boards, projects, etc.?
  (db/transact! [[:db.fn/retractEntity org-id]])
  {:body ""})

(q/defquery settings
  [{:keys [org-id]}]
  ;; all the settings that can be changed
  (q/pull `[~@entity.data/listing-fields
            ~@entity.data/site-fields]
          org-id))

(q/defquery show
  {:prepare [az/require-account!
             (az/with-roles :org-id)]}
  [{:keys [org-id membership/roles]}]
  (->> (dl/resolve-id org-id)
       (q/pull `[~@entity.data/listing-fields
                 ~@entity.data/site-fields
                 {:entity/_parent ~entity.data/listing-fields}])
       (merge {:membership/roles roles})))

(q/defquery members
  {:prepare [az/require-account!]}
  [{:keys [org-id]}]
  (mapv (q/pull `[~@entity.data/id-fields
                  {:membership/entity [:entity/id]}
                  {:membership/member ~entity.data/listing-fields}
                  :membership/roles])
        (member.data/memberships (db/entity org-id)
                                 (xf/sort-by :entity/created-at u/compare:desc))))

(q/defquery pending-memberships
  {:prepare [az/require-account!]}
  [{:keys [org-id]}]
  (mapv (q/pull `[~@entity.data/id-fields
                  {:membership/entity [:entity/id]}
                  {:membership/member ~entity.data/listing-fields}
                  :membership/roles])
        (member.data/pending-memberships (db/entity org-id)
                                         (xf/sort-by :entity/created-at u/compare:desc))))

(q/defx search-once
  [{:as   params
    :keys [org-id q]}]
  (when q
    {:q        q
     :boards   (dl/q (u/template
                       `[:find [(pull ?board [~@entity.data/listing-fields]) ...]
                         :in $ ?terms ?org
                         :where
                         [?board :entity/parent ?org]
                         [(fulltext $ ?terms {:top 100}) [[?board _ _]]]])
                     q
                     org-id)
     :projects (dl/q (u/template
                      `[:find [(pull ?project [~@entity.data/listing-fields
                                               {:entity/parent [:entity/id]}]) ...]
                        :in $ ?terms ?org
                        :where
                        [?board :entity/parent ?org]
                        [?project :entity/parent ?board]
                        [(fulltext $ ?terms {:top 100}) [[?project _ _]]]])
                     q
                     org-id)}))

(q/defx new!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id org]}]
  (let [org    (-> (dl/new-entity org :org :by account-id)
                   (validate/conform :org/as-map))
        member (-> {:membership/entity org
                    :membership/member account-id
                    :membership/roles  #{:role/org-admin}}
                   (dl/new-entity :membership))]
    (db/transact! [member])
    org))

(q/defx create-invitation!
  "Creates or resurects pending org membership"
  {:prepare [az/with-account-id!
             (az/assert-can-admin-or-self :entity-id)]}
  [{:keys [invitee-account-id entity-id]}]
  (db/transact! (if-let [entity-member-id (az/deleted-membership-id invitee-account-id entity-id)]
                  [(-> {:db/id entity-member-id
                        :entity/created-at (java.util.Date.)
                        :entity/deleted-at sch/DELETED_SENTINEL}
                       (assoc :membership/member-approval-pending? true)
                       (notification.data/assoc-recipients [(sch/wrap-id invitee-account-id)]))]
                  [(-> (member.data/new-entity-with-membership (sch/wrap-id entity-id)
                                                   invitee-account-id
                                                   #{})
                       (assoc :membership/member-approval-pending? true)
                       (notification.data/assoc-recipients [(sch/wrap-id invitee-account-id)])
                       (validate/assert :membership/as-map))]))
  {:body ""})

(q/defquery org-membership
  {:prepare [az/with-account-id!]}
  [{:keys [account-id org-id]}]
  (when-let [member-id (or (az/membership-id account-id org-id)
                           (az/deleted-membership-id account-id org-id))]
    (q/pull `[~@entity.data/id-fields
              :entity/deleted-at
              :membership/member-approval-pending?
              {:membership/entity [:entity/id]}
              {:membership/member ~entity.data/listing-fields}
              :membership/roles]
            member-id)))

(q/defx approve-membership!
  {:prepare [az/with-account-id!
             #?(:cljs
                (fn [_ params]
                  ;; making sure the necessary data for the next `:prepare` step is loaded on the client
                  (org-membership {:org-id (:org-id params)})
                  nil))
             (az/with-member-id! :org-id)
             (fn [_ params]
               (when-not (:membership/member-approval-pending? (db/entity (:member-id params)))
                 (validate/permission-denied!)))]}
  [{:keys [account-id org-id member-id]}]
  (let [admins (->> (db/where [[:membership/entity (sch/wrap-id org-id)]
                               (comp :role/org-admin :membership/roles)])
                    (map (comp sch/wrap-id :membership/member)))]
    (db/transact! [(-> {:db/id member-id
                        :membership/member-approval-pending? false}
                       ;; TODO need to remove old notifications
                       (notification.data/assoc-recipients admins))])
    {:body ""}))

(q/defquery search-users
  {:prepare [az/with-account-id!]}
  [{:keys [filter-term]}]
  (dl/q (u/template
         `[:find [(pull ?account [~@entity.data/listing-fields]) ...]
           :in $ ?terms
           :where
           [?account :entity/kind :account]
           [(fulltext $ ?terms {:top 10}) [[?account _ _]]]])
        filter-term))
