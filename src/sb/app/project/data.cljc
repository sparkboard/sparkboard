(ns sb.app.project.data
  (:require [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
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
     :project/sticky?           {:doc "Show project with border at top of project list"
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
                                     (? :project/sticky?)
                                     (? :project/open-requests)
                                     (? :entity/description)]
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
           (let [project (q/pull `[:entity/id
                                   :entity/kind
                                   :entity/title
                                   :entity/description
                                   :entity/admission-policy
                                   {:entity/video [:video/url]}
                                   {:project/badges [:badge/label
                                                     :badge/color]}
                                   :entity/field-entries
                                   :entity/draft?
                                   :entity/deleted-at
                                   :project/sticky?
                                   {:membership/_entity [:entity/id
                                                         :entity/kind
                                                         :entity/created-at
                                                         :membership/roles
                                                         {:membership/member [:account/display-name
                                                                              :entity/id
                                                                              :entity/kind
                                                                              {:image/avatar [:entity/id]}]}]}
                                   {:entity/parent
                                    [~@entity.data/listing-fields
                                     :board/sticky-color
                                     {:entity/project-fields ~field.data/field-keys}]}]
                                 project-id)]
             (when (and project (not (:entity/deleted-at project)))
               (merge project {:membership/roles roles})))))

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
                                                           (az/membership-id account-id (:entity/parent project))
                                                           #{:role/project-admin})]
    (validate/assert project :project/as-map)
    (db/transact! [membership])
    {:entity/id (:entity/id project)}))

(q/defx delete!
  "Mutation fn. Marks project as deleted by given project-id."
  [_req {:keys [project-id]}]
  ;; TODO: auth
  (db/transact! [[:db/add [:entity/id project-id] :entity/deleted-at (java.util.Date.)]])
  {:body ""})
