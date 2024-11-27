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

(defn strip-deleted-posts [entity]
  (update entity :post/_parent
          update-vals (comp strip-deleted-posts
                            #(cond-> %
                               (sch/deleted? %)
                               (dissoc :entity/created-by :post/text)))))

(q/defquery show
  {:endpoint/public? true ;; TODO do we need to restrict by board visibility?
   :prepare [(az/with-roles :project-id)]}
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
                                            :membership/member-approval-pending?
                                            {:membership/member ~entity.data/listing-fields}]}
                      ~discussion.data/posts-with-comments-field
                      {:entity/parent
                       [~@entity.data/listing-fields
                        :board/sticky-color
                        {:entity/project-fields ~field.data/field-keys}]}]
                    project-id)
            (u/guard (complement sch/deleted?))
            strip-deleted-posts
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


