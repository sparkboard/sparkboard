(ns sb.app.project.data
  (:require [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
            [sb.app.discussion.data :as discussion.data]
            [sb.app.field.data :as field.data]
            [sb.app.membership.data :as member.data]
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
                                     (? :discussion/followers)]
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
                                            {:membership/member [:account/display-name
                                                                 :entity/id
                                                                 :entity/kind
                                                                 {:image/avatar [:entity/id]}]}]}
                      ~discussion.data/posts-with-comments-field
                      {:entity/parent
                       [~@entity.data/listing-fields
                        :board/sticky-color
                        {:entity/project-fields ~field.data/field-keys}]}]
                    project-id)
            (u/guard (complement sch/deleted?))
            (merge {:membership/roles roles}))))

(q/defquery fields [{:keys [board-id]}]
  (-> (q/pull `[~@entity.data/id-fields
                {:entity/project-fields ~field.data/field-keys}] board-id)
      :entity/project-fields))

(q/defx new!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} project]
  ;; TODO
  ;; verify that user is allowed to create a new project in parent
  (let [project    (dl/new-entity project :project :by account-id)
        membership (member.data/new-entity-with-membership project
                                                           (->> (:entity/parent project)
                                                                (iterate (comp :entity/parent dl/entity))
                                                                (take-while identity)
                                                                (some (partial az/membership-id account-id)))
                                                           #{:role/project-admin})]
    (validate/assert project :project/as-map)
    (validate/assert (update membership :membership/entity sch/wrap-id) :membership/as-map)
    (db/transact! [membership])
    {:entity/id (:entity/id project)}))

(q/defx delete!
  "Mutation fn. Marks project as deleted by given project-id."
  [{:keys [project-id account-id]}]
  (az/auth-guard! (az/editor-role? (az/all-roles account-id (dl/entity project-id)))
      "Not authorized to delete"
    (db/transact! [[:db/add [:entity/id project-id] :entity/deleted-at (java.util.Date.)]])
    {:body ""}))


(q/defx join!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id project-id]}]
  (az/auth-guard! (= :admission-policy/open (:entity/admission-policy (dl/entity project-id)))
      "Project is invite only"
    (if-let [member-id (-> account-id
                           (az/membership-id (:entity/parent (dl/entity (sch/wrap-id project-id))))
                           (az/deleted-membership-id project-id))]
      (db/transact! [{:db/id member-id
                      :entity/deleted-at sch/DELETED_SENTINEL}])
      (let [membership (member.data/new-entity-with-membership (sch/wrap-id project-id)
                                                               (az/membership-id account-id (:entity/parent (dl/entity project-id)))
                                                               #{})]
        (validate/assert membership :membership/as-map)
        (db/transact! [membership])))
    {:body ""}))

(q/defx leave!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id project-id]}]
  (when-let [member-id (-> account-id
                           (az/membership-id (:entity/parent (dl/entity (sch/wrap-id project-id))))
                           (az/membership-id project-id))]
    (db/transact! [{:db/id member-id
                    :entity/deleted-at (java.util.Date.)}]))
  {:body ""})
