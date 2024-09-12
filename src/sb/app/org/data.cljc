(ns sb.app.org.data
  (:require [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.util :as u]
            [sb.validate :as validate]))

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
