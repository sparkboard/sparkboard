(ns sparkboard.app.org.data
  (:require [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.app.domain.data :as domain.data]
            [sparkboard.app.entity.data :as entity.data]
            [sparkboard.app.member.data :as member.data]
            [sparkboard.authorize :as az]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.query :as q]
            [sparkboard.routing :as routes]
            [sparkboard.schema :as sch :refer [? s-]]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.app.views.ui :as ui]
            [sparkboard.app.views.header :as header]
            [sparkboard.icons :as icons]
            [sparkboard.util :as u]
            [sparkboard.validate :as validate]
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
                                    (? :entity/domain)
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
  (q/pull `[~@entity.data/fields]
          org-id))

(q/defquery show
  {:prepare [az/with-account-id!
             (member.data/member:log-visit! :org-id)]}
  [{:keys [org-id]}]
  (q/pull `[~@entity.data/fields
            {:entity/_parent ~entity.data/fields}]
          (dl/resolve-id org-id)))

(q/defx search-once
  [{:as   params
    :keys [org-id q]}]
  (when q
    {:q        q
     :boards   (dl/q (u/template
                       `[:find [(pull ?board ~entity.data/fields) ...]
                         :in $ ?terms ?org
                         :where
                         [?board :entity/parent ?org]
                         [(fulltext $ ?terms {:top 100}) [[?board _ _]]]])
                     q
                     org-id)
     :projects (->> (dl/q (u/template
                            `[:find [(pull ?project [~@entity.data/fields
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
  (validate/assert-can-edit! id account-id)
  (let [org (validate/conform org :org/as-map)]
    (db/transact! [org])
    {:body org}))

(q/defx new!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id org]}]
  (let [org    (-> (dl/new-entity org :org :by account-id)
                   (validate/conform :org/as-map))
        member (-> {:member/entity  org
                    :member/account account-id
                    :member/roles   #{:role/owner}}
                   (dl/new-entity :member))]
    (db/transact! [member])
    org))