(ns sb.app.org.data
  (:require [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.domain-name.data :as domain.data]
            [sb.app.entity.data :as entity.data]
            [sb.app.member.data :as member.data]
            [sb.authorize :as az]
            [sb.i18n :refer [t]]
            [sb.query :as q]
            [sb.routing :as routes]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.app.views.ui :as ui]
            [sb.app.views.header :as header]
            [sb.icons :as icons]
            [sb.util :as u]
            [sb.validate :as validate]
            [yawn.hooks :as h]
            [yawn.view :as v]))

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
  (q/pull `[~@entity.data/entity-keys]
          org-id))

(q/defquery show
  {:prepare [az/with-account-id!]}
  [{:keys [org-id]}]
  (q/pull `[~@entity.data/entity-keys
            {:entity/_parent ~entity.data/entity-keys}]
          (dl/resolve-id org-id)))

(q/defx search-once
  [{:as   params
    :keys [org-id q]}]
  (when q
    {:q        q
     :boards   (dl/q (u/template
                       `[:find [(pull ?board ~entity.data/entity-keys) ...]
                         :in $ ?terms ?org
                         :where
                         [?board :entity/parent ?org]
                         [(fulltext $ ?terms {:top 100}) [[?board _ _]]]])
                     q
                     org-id)
     :projects (->> (dl/q (u/template
                            `[:find [(pull ?project [~@entity.data/entity-keys
                                                     :project/sticky?
                                                     {:entity/parent [:entity/id]}]) ...]
                              :in $ ?terms ?org
                              :where
                              [?board :entity/parent ?org]
                              [?project :entity/parent ?board]
                              [(fulltext $ ?terms {:top 100}) [[?project _ _]]]])
                          q
                          org-id)
                    (remove :project/sticky?))}))

(q/defx settings!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} {:as org :keys [entity.data/id]}]
  (validate/assert-can-edit! account-id id)
  (let [org (validate/conform org :org/as-map)]
    (db/transact! [org])
    {:body org}))

(q/defx new!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id org]}]
  (let [org    (-> (dl/new-entity org :org :by account-id)
                   (validate/conform :org/as-map))
        member (-> {:membership/entity  org
                    :membership/account account-id
                    :membership/roles   #{:role/admin}}
                   (dl/new-entity :membership))]
    (db/transact! [member])
    org))