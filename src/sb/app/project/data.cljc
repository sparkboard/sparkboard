(ns sb.app.project.data
  (:require [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
            [sb.app.discussion.data :as discussion.data]
            [sb.app.field.data :as field.data]
            [sb.app.membership.data :as member.data]
            [sb.app.notification.data :as notification.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.validate :as validate]
            [sb.util :as u]))

(sch/register!
  (merge

    {:social/sharing-button {s- [:enum
                                 :social.sharing-button/facebook
                                 :social.sharing-button/twitter
                                 :social.sharing-button/qr-code]}}

    {:request/text {:doc "Free text description of the request"
                    s-   :string}
     :request/map  {s- [:map {:closed true} :request/text]}}

    {:project/open-requests     {:doc "Currently active requests for help"
                                 s-   [:sequential :request/map]},
     :project/community-actions {s- [:sequential :community-action/as-map]}
     :project/approved?         {:doc "Set by an admin when :board/new-projects-require-approval? is enabled. Unapproved projects are hidden."
                                 s-   :boolean}
     :project/badges            {:doc "A badge is displayed on a project with similar formatting to a tag. Badges are ad-hoc, not defined at a higher level.",
                                 s-   [:vector :content/badge]}
     :project/number            {:doc  "Number assigned to a project by its board (stored as text because may contain annotations)",
                                 :todo "This could be stored in the board entity, a map of {project, number}, so that projects may participate in multiple boards"
                                 s-    :string}
     :entity/admission-policy   {:doc "A policy for who may join a team."
                                 s-   [:enum
                                       :admission-policy/open
                                       :admission-policy/invite-only]}
     :project/admin-description {:doc "A description field only writable by an admin"
                                 s-   :prose/as-map}
     :entity/archived?          {:doc "Marks a project inactive, hidden."
                                 s-   :boolean}
     :project/as-map            {s- [:map {:closed true}
                                     :entity/id
                                     :entity/kind
                                     :entity/parent
                                     :entity/title
                                     :entity/created-at
                                     :entity/admission-policy
                                     (? :entity/uploads)
                                     (? :entity/updated-at)
                                     (? :entity/draft?)
                                     (? :entity/archived?)
                                     (? :entity/field-entries)
                                     (? :entity/video)
                                     (? :entity/created-by)
                                     (? :entity/deleted-at)
                                     (? :entity/modified-by)
                                     (? :membership/_entity)
                                     (? [:project/card-classes {:doc          "css classes for card"
                                                                :to-deprecate true}
                                         [:sequential :string]])
                                     (? :project/approved?)
                                     (? :project/badges)
                                     (? :project/number)
                                     (? :project/admin-description)
                                     (? :project/open-requests)
                                     (? :entity/description)
                                     (? :post/do-not-follow)
                                     (? :post/followers)]
                                 }
     :community-action/as-map   {s- [:map-of
                                     :community-action/label
                                     :community-action/action
                                     (? :community-action/hint)
                                     (? :community-action.chat)]}
     :community-action/label    {s- :string}
     :community-action/action   (merge sch/keyword
                                       {s- [:enum
                                            :community-action.action/copy-link
                                            :community-action.action/chat]})
     :community-action/hint     {s- :string}}))

(q/defquery show
  {:prepare [(az/with-roles :project-id)]}
  [{:keys [project-id membership/roles]}]
  (u/timed `show
    (some-> (q/pull `[:entity/id
                      :entity/kind
                      :entity/title
                      :entity/description
                      :entity/admission-policy
                      {:entity/video [:video/url]}
                      {:project/badges [:badge/label
                                        :badge/color]}
                      :project/open-requests
                      :entity/field-entries
                      :entity/draft?
                      :entity/deleted-at
                      {:membership/_entity [:entity/id
                                            :entity/kind
                                            :entity/created-at
                                            :entity/deleted-at
                                            :membership/roles
                                            {:membership/member ~entity.data/listing-fields}]}
                      ~discussion.data/posts-with-comments-field
                      {:entity/parent
                       [~@entity.data/listing-fields
                        :board/sticky-color
                        {:entity/project-fields ~field.data/field-keys}]}]
                    project-id)
            (u/guard (complement sch/deleted?))
            (merge {:membership/roles roles})
            (as-> project
                (assoc project :__profiles (mapv #(db/pull '[:entity/id :entity/kind
                                                             {:membership/entity [:entity/id :entity/member-tags]}
                                                             {:entity/tags [:entity/id
                                                                            :tag/label
                                                                            :tag/color]}
                                                             {:membership/member [:entity/id]}]
                                                           (az/membership-id (:membership/member %)
                                                                             (:entity/parent project)))
                                                 (:membership/_entity project)))))))

(q/defquery fields [{:keys [board-id]}]
  (-> (q/pull `[~@entity.data/id-fields
                {:entity/project-fields ~field.data/field-keys}] board-id)
      :entity/project-fields))

(q/defx new!
  {:prepare [az/with-account-id!
             (az/with-member-id! (comp :entity/parent :project))]}
  [{:keys [account-id project]}]
  ;; TODO
  ;; verify that user is allowed to create a new project in parent
  ;; check if project creation can even be restricted for board members
  (let [project    (dl/new-entity project :project :by account-id)
        membership (member.data/new-entity-with-membership project account-id #{:role/project-admin})]
    (validate/assert project :project/as-map)
    (validate/assert (update membership :membership/entity sch/wrap-id) :membership/as-map)
    (db/transact! [membership])
    {:entity/id (:entity/id project)}))

(q/defx delete!
  "Mutation fn. Marks project as deleted by given project-id."
  {:prepare [az/with-account-id!
             (member.data/assert-can-edit :project-id)]}
  [{:keys [project-id account-id]}]
  (db/transact! [[:db/add [:entity/id project-id] :entity/deleted-at (java.util.Date.)]])
  {:body ""})

(q/defx join!
  {:prepare [az/with-account-id!
             (az/with-member-id! (comp :entity/parent dl/entity :project-id))
             (fn [_ {:keys [project-id]}]
               (az/auth-guard! (= :admission-policy/open (:entity/admission-policy (dl/entity project-id)))
                   "Project is invite only"))]}
  [{:keys [account-id project-id]}]
  (let [admins (->> (db/where [[:membership/entity (sch/wrap-id project-id)]
                               (comp :role/project-admin :membership/roles)])
                    (map (comp sch/wrap-id :membership/member)))]
    (db/transact! (if-let [project-member-id (az/deleted-membership-id account-id project-id)]
                    [(-> {:db/id project-member-id
                          :entity/created-at (java.util.Date.)
                          :entity/deleted-at sch/DELETED_SENTINEL}
                         (notification.data/assoc-recipients admins))]
                    [(-> (member.data/new-entity-with-membership (sch/wrap-id project-id)
                                                                 account-id
                                                                 #{})
                         (notification.data/assoc-recipients admins)
                         (validate/assert :membership/as-map))])))
  {:body ""})

(q/defx leave!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id project-id]}]
  (when-let [member-id (az/membership-id account-id project-id)]
    (db/transact! [{:db/id member-id
                    :entity/deleted-at (java.util.Date.)}]))
  {:body ""})
