(ns sb.app.note.data
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
 {:note/outline-color {s- :html/color}
  :note/badges        {:doc "A badge is displayed on a project with similar formatting to a tag. Badges are ad-hoc, not defined at a higher level.",
                       s-   [:vector :content/badge]}
  :note/as-map        {s- [:map {:closed true}
                           :entity/id
                           :entity/kind
                           :entity/parent
                           :entity/title
                           :entity/created-at
                           :entity/admission-policy
                           (? :note/outline-color)
                           (? :note/badges)
                           (? [:note/card-classes {:doc          "css classes for card"
                                                   :to-deprecate true}
                               [:sequential :string]])
                           (? :entity/uploads)
                           (? :entity/updated-at)
                           (? :entity/draft?)
                           (? :entity/archived?)
                           (? :entity/fields)
                           (? :entity/field-entries)
                           (? :entity/video)
                           (? :entity/created-by)
                           (? :entity/deleted-at)
                           (? :entity/modified-by)
                           (? :entity/description)]}})


(q/defquery show
  {:prepare [(az/with-roles :note-id)]}
  [{:keys [note-id membership/roles]}]
  (u/timed `show
           (let [note (q/pull `[:entity/id
                                :entity/kind
                                :entity/title
                                :entity/description
                                :entity/admission-policy
                                {:entity/video [:video/url]}
                                {:entity/fields ~field.data/field-keys}
                                :entity/field-entries
                                :entity/draft?
                                :entity/deleted-at
                                :note/badges]
                              note-id)]
             (when (and note (not (sch/deleted? note)))
               (merge note {:membership/roles roles})))))

(q/defx new!
  {:prepare [az/with-account-id!
             (member.data/assert-can-edit (comp :entity/parent :note))]}
  [{:keys [account-id note]}]
  (let [note (dl/new-entity note :note :by account-id)]
    (validate/assert note :note/as-map)
    (db/transact! [note])
    {:entity/id (:entity/id note)}))

(q/defx delete!
  "Mutation fn. Marks note as deleted by given note-id."
  {:prepare [az/with-account-id!
             (member.data/assert-can-edit :note-id)]}
  [{:keys [account-id]} {:keys [note-id]}]
  (db/transact! [[:db/add [:entity/id note-id] :entity/deleted-at (java.util.Date.)]])
  {:body ""})
