(ns sparkboard.app.entity
  (:require [sparkboard.schema :as sch :refer [s- unique-uuid ?]]))

(sch/register!
  (merge

    {:tag/id               unique-uuid
     :tag/background-color {s- :html/color},
     :tag/label            {s- :string},,
     :tag/restricted?      {:doc "Tag may only be modified by an admin of the owner of this tag"
                            s-   :boolean}
     :tag/as-map           {:doc "Description of a tag which may be applied to an entity."
                            s-   [:map {:closed true}
                                  :tag/id
                                  :tag/label
                                  (? :tag/background-color)
                                  (? :tag/restricted?)]}}

    {:entity/id                 unique-uuid
     :entity/title              {:doc         "Title of entity, for card/header display."
                                 s-           :string
                                 :db/fulltext true}
     :entity/kind               {s- [:enum :board :org :collection :member :project :chat :chat.message]}
     :entity/description        {:doc         "Description of an entity (for card/header display)"
                                 s-           :prose/as-map
                                 :db/fulltext true}
     :entity/field-entries      (merge (sch/ref :many :field-entry/as-map)
                                       sch/component)
     :entity/video              {:doc "Primary video for project (distinct from fields)"
                                 s-   :video/entry}
     :entity/public?            {:doc "Contents of this entity can be accessed without authentication (eg. and indexed by search engines)"
                                 s-   :boolean}
     :entity/website            {:doc "External website for entity"
                                 s-   :http/url}
     :entity/social-feed        {s- :social/feed}
     :entity/images             {s- [:map-of
                                     [:qualified-keyword {:namespace :image}]
                                     :http/url]}
     :entity/meta-description   {:doc "Custom description for html page header"
                                 s-   :string}
     :entity/locale-default     {s- :i18n/locale}
     :entity/locale-suggestions {:doc "Suggested locales (set by admin, based on expected users)",
                                 s-   :i18n/locale-suggestions}
     :entity/locale-dicts       {s- :i18n/locale-dicts}
     :entity/created-at         {s-   'inst?
                                 :doc "Date the entity was created"},
     :entity/created-by         (merge (sch/ref :one)
                                       {:doc "Member or account who created this entity"}),
     :entity/deleted-at         {:doc  "Date when entity was marked deleted"
                                 :todo "Excise deleted data after a grace period"
                                 s-    'inst?}
     :entity/modified-by        (merge (sch/ref :one)
                                       {:doc "Member who last modified this entity"}),
     :entity/updated-at         {s-   'inst?
                                 :doc "Date the entity was last modified"}}))