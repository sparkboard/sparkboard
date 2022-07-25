(ns org.sparkboard.schema
  (:refer-clojure :exclude [ref])
  (:require [re-db.schema :as s]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.experimental.lite :as l]
            [malli.registry :as mr]
            [malli.provider :as mp]
            [re-db.in-memory :as mem]
            [re-db.api :as d]
            [clojure.string :as str]))

(def !registry (atom (m/default-schemas)))
(mr/set-default-registry! (mr/mutable-registry !registry))

(defn register! [m] (swap! !registry merge m))

(comment
 (reset! !registry (m/default-schemas))
 (swap! !registry assoc :board/policies [:map-of [:qualified-keyword {:namespace :action}] :map #_:action/policy])
 (into (sorted-map) @!registry)
 (@!registry :field/spec)
 (gen :board/policies)
 (gen :action/policy)

 (gen [:map-of [:qualified-keyword {:namespace 'action}] :string])
 (gen [:qualified-keyword {:namespace :x}]))

(defn gen [s] (take 3 (repeatedly #(mg/generate s))))

;; TODO
;; malli for every field,
;; import into datalevin

(def ref (merge s/ref {:spark/schema :db/lookup-ref}))


(def str? [:string {:optional true}])
(def bool? [:boolean {:optional true}])
(def lookup-ref? :db/lookup-ref)

(def schema :spark/schema)
(defn system [k] {:spark/system k})
(def locale? [:re #"[a-z]{2}$"])

(comment
 ;; gen
 (gen [:map-of locale? [:map-of :string :string]])
 (gen [pos-int?])
 (gen field?)
 (gen (l))
 )

(def s- :spark/schema)

(defn lookup-ref [ks]
  [:tuple (into [:enum] ks) :string])

(defn ref
  ([cardinality] (case cardinality :one (merge s/ref
                                               s/one
                                               {s- :db/lookup-ref})
                                   :many (merge s/ref
                                                s/many
                                                {s- [:sequential :db/lookup-ref]})))
  ([cardinality ks]
   (case cardinality :one (merge s/ref
                                 s/one
                                 {s- (lookup-ref ks)})
                     :many (merge s/ref
                                  s/many
                                  {s- [:sequential (lookup-ref ks)]}))))

(def unique-string-id (merge s/unique-id
                             s/string
                             {s- :string}))
(def bool (merge s/boolean
                 {s- :boolean}))

(def http-url {s- :http/url})
(def html-color {s- :html/color})


(def date {:db/valueType :db.type/instant
           })


(defn ? [k]
  (if (keyword? k)
    [k {:optional true}]
    (do (assert (vector? k))
        (if (map? (second k))
          (update k 1 assoc :optional true)
          (into [(first k) {:optional true}] (rest k))))))

(def extra-schema
  (doto {:action/policy {:doc "A policy describes how an action is restricted"
                         s- [:map {:closed true} :policy/requires-role]}
         :policy/requires-role {:doc "Policy: the action may only be completed by a member who has one of the provided roles."
                                s- [:set :grant/role]},
         :badge/label {s- :string
                       :spark/user-text true},
         :board/id unique-string-id
         :board/is-template? {:doc "Board is only used as a template for creating other boards",
                              s- :boolean},
         :board/labels {:unsure "How can this be handled w.r.t. locale?"
                        s- [:map-of [:enum
                                     :label/member.one
                                     :label/member.many
                                     :label/project.one
                                     :label/project.many] :string]},
         :board/org (ref :one #{:org/id})
         :board/policies {s- [:map-of [:qualified-keyword {:namespace :action}] :action/policy]},
         :board/registration-codes {s- [:map-of :string [:map {:closed true} [:registration-code/active? :boolean]]]}
         :board/slack.team (ref :one #{:slack.team/id})
         :board/webhooks {s- [:map-of :spark/event [:map {:closed true} [:webhook/url :http/url]]]}
         :board.member-vote/open? {:doc "Opens a community vote (shown as a tab on the board)"
                                   s- :boolean}
         :board.landing-page/description-content {:doc "Primary description of a board, displayed in header"
                                                  s- :text-content/block}
         :board.landing-page/instruction-content {:doc "Secondary instructions for a board, displayed above projects"
                                                  s- :text-content/block},
         :board.landing-page/learn-more-url {:doc ""
                                             s- :http/url},
         :board.member/fields {s- [:sequential :entity/field.spec]}
         :board.member/max-projects {:doc "Set a maximum number of projects a member may join"
                                     s- :int},
         :board.member/private-messaging? {:doc "Enable the private messaging feature for this board"
                                           s- :boolean},
         :board.member/tags {s- [:sequential :entity/tag]}
         :board.project/fields {s- [:sequential :entity/field.spec]}
         :board.project/max-members {:doc "Set a maximum number of members a project may have"
                                     s- :int},
         :board.project/sharing-buttons {:doc "Which social sharing buttons to display on project detail pages",
                                         s- [:map-of :social/sharing-button :boolean]},
         :board.project/show-numbers? {:doc "Show 'project numbers' for this board"
                                       s- :boolean},
         :board.project/sticky-border-color {:doc "Border color for sticky projects", s- :html/color},
         :board.registration.invitation-email/body-text {:doc "Body of email sent when inviting a user to a board."
                                                         s- :string},
         :board.registration/newsletter-subscription-field? {:doc "During registration, request permission to send the user an email newsletter"
                                                             s- :boolean},
         :board.registration/open? {:doc "Allows new registrations via the registration page. Does not affect invitations.",
                                    s- :boolean},
         :board.registration/pre-registration-content {:doc "Content displayed on registration screen (before user chooses provider / enters email)"
                                                       s- :text-content/block},
         :board.registration/register-at-url {:doc "URL to redirect user for registration (replaces the Sparkboard registration page, admins are expected to invite users)",
                                              s- :http/url},
         :collection/id unique-string-id
         :collection/boards (ref :many #{:board/id})
         :member-vote.entry/_member {s- [:sequential :entity/member-vote.entry]}
         :member-vote.entry/board (ref :one #{:board/id})
         :member-vote.entry/id {:doc "ID composed of board-id:member-id"
                                :todo "Replace with tuple attribute of board+member?"
                                s- :string}
         :member-vote.entry/member (ref :one #{:member/id})
         :member-vote.entry/project (ref :one #{:project/id}),

         :text-content/format {s- [:enum
                                   :text.format/html
                                   :text.format/markdown]},
         :text-content/string {s- :string},
         :db/lookup-ref {s- [:tuple :qualified-keyword :string]},
         :discussion/id unique-string-id
         :discussion/board (ref :one #{:board/id})
         :discussion/followers (ref :many #{:member/id}),
         :discussion/posts (ref :many #{:post/id})
         :discussion/project (ref :one #{:project/id})
         ;; TODO - URL-decode domain/name
         :domain/url {s- :http/url}
         :domain/target-type {s- [:enum
                                  :domain/url
                                  :domain/entity]}
         :domain/entity (merge (ref :one #{:board/id :org/id :collection/id})
                               {:doc "The entity this domain points to",}),
         :domain/name {:doc "A complete domain name, eg a.b.com",
                       s- :string},
         :entity/board {s- [:map {:closed true}
                            (? :board/is-template?)
                            (? :board/labels)
                            (? :board/policies)
                            (? :board/registration-codes)
                            (? :board/slack.team)
                            (? :board/webhooks)
                            (? :board.member-vote/open?)
                            (? :board.landing-page/description-content)
                            (? :board.landing-page/instruction-content)
                            (? :board.landing-page/learn-more-url)
                            (? :board.member/fields)
                            (? :board.member/max-projects)
                            (? :board.member/tags)
                            (? :board.project/fields)
                            (? :board.project/max-members)
                            (? :board.project/sharing-buttons)
                            (? :board.project/show-numbers?)
                            (? :board.project/sticky-border-color)
                            (? :board.registration.invitation-email/body-text)
                            (? :board.registration/pre-registration-content)
                            (? :board.registration/newsletter-subscription-field?)
                            (? :board.registration/register-at-url)
                            (? :html/css)
                            (? :html/js)
                            (? :html.meta/description)
                            (? :http/domain)
                            (? :i18n/default-locale)
                            (? :i18n/extra-translations)
                            (? :i18n/suggested-locales)
                            (? :spark/image-urls)
                            (? :social/feed)
                            (? :tag/_managed-by)
                            (? :ts/deleted-at)
                            :board/id
                            :board/org
                            :board.member/private-messaging?
                            :board.registration/open?
                            :ts/created-at
                            :visibility/public?
                            :content/title]},
         :entity/collection {s- [:map {:closed true}
                                 (? :spark/image-urls)
                                 :collection/boards
                                 :collection/id
                                 :http/domain
                                 :content/title]}
         :entity/member-vote.entry {s- [:map {:closed true}
                                        :member-vote.entry/id
                                        :member-vote.entry/board
                                        :member-vote.entry/member
                                        :member-vote.entry/project]}
         :entity/member-vote {s- [:map {:closed true}
                                  :member-vote/board
                                  :member-vote/open?]},
         :entity/discussion {s- [:map {:closed true}
                                 :discussion/board
                                 :discussion/followers
                                 :discussion/id
                                 :discussion/posts
                                 :discussion/project
                                 :ts/created-at]}
         :entity/domain {s- [:multi {:dispatch :domain/target-type}
                             [:domain/url
                              [:map {:closed true}
                               [:domain/target-type [:= :domain/url]]
                               :domain/url
                               :domain/name]]
                             [:domain/entity
                              [:map {:closed true}
                               [:domain/target-type [:= :domain/entity]]
                               :domain/entity
                               :domain/name]]]},

         :entity/field {s- [:map {:closed true}
                            :field/field.spec
                            :field/id
                            :field/parent
                            :field/value]}
         :entity/field.spec {:doc "Description of a field."
                             :todo ["Field specs should be definable at a global, org or board level."
                                    "Orgs/boards should be able to override/add field.spec options."
                                    "Field specs should be globally merged so that fields representing the 'same' thing can be globally searched/filtered?"]
                             s- [:map {:closed true}
                                 (? :field.spec/hint)
                                 (? :field.spec/label)
                                 (? :field.spec/options)
                                 (? :field.spec/required?)
                                 (? :field.spec/show-as-filter?)
                                 (? :field.spec/show-at-create?)
                                 (? :field.spec/show-on-card?)
                                 :field.spec/id
                                 :field.spec/managed-by
                                 :field.spec/order
                                 :field.spec/type]}
         :entity/grant {s- [:map {:closed true}
                            :grant/id
                            :grant/roles
                            :grant/entity
                            :grant/member]}
         :entity/member {s- [:map {:closed true}
                             (? :member-vote.entry/_member)
                             (? :field/_entity)
                             (? :grant/_member)
                             (? :member.admin/suspected-fake?)
                             (? :member/image-url)
                             (? :member/newsletter-subscription?)
                             (? :member/tags)
                             (? :ts/deleted-at)
                             (? :ts/modified-by)
                             :member/board
                             :member/email-frequency
                             :member/firebase-account
                             :member/id
                             :member/name
                             :member/new?
                             :member/project-participant?
                             :member.admin/inactive?
                             :ts/created-at
                             :ts/updated-at]},
         :entity/notification {s- [:map {:closed true}
                                   (? :notification/discussion)
                                   (? :notification/member)
                                   (? :notification/post)
                                   (? :notification/post.comment)
                                   (? :notification/project)
                                   (? :notification/thread)
                                   (? :notification/thread.message.text)
                                   :notification/emailed?
                                   :notification/id
                                   :notification/recipient
                                   :notification/type
                                   :notification/viewed?
                                   :ts/created-at]},
         :entity/org {s- [:map {:closed true}
                          (? :org.board/show-org-tab?)
                          (? :spark/image-urls)
                          (? :org.board/template-default)
                          (? :social/feed)
                          (? :i18n/default-locale)
                          (? :i18n/suggested-locales)
                          :content/title
                          :org/id
                          :http/domain
                          :visibility/public?
                          :ts/created-by]},
         :entity/post {s- [:map {:closed true}
                           :post/id]}
         :entity/post.comment {s- [:map {:closed true}
                                   :ts/created-at
                                   :ts/created-by
                                   :post.comment/id
                                   :post.comment/text]},
         :entity/project {s- [:map {:closed true}
                              (? :field/_entity)
                              (? :grant/_entity)
                              (? :html/card-classes)
                              (? :project/open-requests)
                              (? :project/summary-text)
                              (? :project/viable-team?)
                              (? :project/video)
                              (? :project.admin/approved?)
                              (? :project.admin/badges)
                              (? :project.admin/board.number)
                              (? :project.admin/description-content)
                              (? :project.admin/sticky?)
                              (? :project.admin/inactive?)
                              (? :ts/deleted-at)
                              (? :ts/modified-by)
                              :project/board
                              :project/id
                              :content/title
                              :ts/created-at
                              :ts/created-by
                              :ts/updated-at]},
         :entity/slack.broadcast {s- [:map {:closed true}
                                      (? :slack.broadcast/response-channel)
                                      (? :slack.broadcast/response-thread)
                                      (? :slack.broadcast/slack.broadcast.replies)
                                      (? :slack.broadcast/slack.team)
                                      (? :slack.broadcast/slack.user)
                                      :slack.broadcast/id
                                      :slack.broadcast/text]},
         :entity/slack.broadcast.reply {s- [:map {:closed true}
                                            :slack.broadcast.reply/id
                                            :slack.broadcast.reply/text
                                            :slack.broadcast.reply/slack.user
                                            :slack.broadcast.reply/slack.channel]}
         :entity/slack.channel {s- [:map {:closed true}
                                    :slack.channel/id
                                    :slack.channel/project
                                    :slack.channel/slack.team]},
         :entity/slack.team {s- [:map {:closed true}
                                 (? :slack.team/custom-messages)
                                 (? :slack.team/invite-link)
                                 :slack.team/board
                                 :slack.team/id
                                 :slack.team/name
                                 :slack.team/slack.app]},
         :entity/slack.user {s- [:map {:closed true}
                                 :slack.user/firebase-account
                                 :slack.user/id
                                 :slack.user/slack.team]},
         :entity/tag {:doc "Description of a tag which may be applied to an entity."
                      s- [:map {:closed true}
                          (? :tag/background-color)
                          (? :tag/restricted?)
                          :tag/label
                          :tag/id
                          :tag/managed-by]},
         :entity/thread {s- [:map {:closed true}
                             :ts/created-at
                             :ts/updated-at
                             :thread/id
                             :thread/members
                             :thread/messages
                             :thread/read-by]},
         :entity/thread.message {s- [:map {:closed true}
                                     :thread.message/id
                                     :thread.message/text
                                     :ts/created-by
                                     :ts/created-at]}
         :field/_entity {s- [:sequential :entity/field]}
         :field/id (merge unique-string-id
                          {:todo "Should be a tuple field of [:field/parent :field/spec] ?"}),
         :field/parent (ref :one #{:member/id :project/id}),
         :field/field.spec (ref :one #{:field.spec/id}),
         :field.image/url {s- :http/url},
         :field.link-list/item {s- [:map {:closed true}
                                    :field.link-list/text
                                    :field.link-list/url]}
         :field.link-list/text {s- :string},
         :field.link-list/url {s- :http/url},
         :field.select/value {s- :string},
         :field.spec/hint {s- :string},
         :field.spec/id {s- :string}
         :field.spec/label {s- :string},
         :field.spec/managed-by (ref :one #{:board/id :org/id}),
         :field.spec/options {s- (? [:sequential :field.spec/option])},
         :field.spec/option {s- [:map {:closed true}
                                 (? :field.spec.option/color)
                                 (? :field.spec.option/default?)
                                 (? :field.spec.option/value)
                                 :field.spec.option/label]}
         :field.spec/order {s- :int},
         :field.spec/required? {s- :boolean},
         :field.spec/show-as-filter? {:doc "Use this field as a filtering option"
                                      s- :boolean},
         :field.spec/show-at-create? {:doc "Ask for this field when creating a new entity"
                                      s- :boolean},
         :field.spec/show-on-card? {:doc "Show this field on the entity when viewed as a card"
                                    s- :boolean},
         :field.spec/type {s- [:enum
                               :field.type/image
                               :field.type/video
                               :field.type/select
                               :field.type/link-list
                               :field.type/text-content
                               :field.type/text-content]}
         :field/value {s- [:multi {:dispatch 'first}
                           [:field.type/image [:tuple [:= :field.type/image]
                                               [:map {:closed true} :field.image/url]]]
                           [:field.type/link-list [:tuple [:= :field.type/link-list]
                                                   [:map {:closed true}
                                                    [:field.link-list/items
                                                     [:sequential :field.link-list/item]]]]]
                           [:field.type/select [:tuple [:= :field.type/select]
                                                [:map {:closed true} :field.select/value]]]
                           [:field.type/text-content [:tuple [:= :field.type/text-content] :text-content/block]]
                           [:field.type/video [:tuple [:= :field.type/video] :field.video/entry]]]}
         :field.video/entry {s- [:multi {:dispatch :field.video/format}
                                 [:video.format/vimeo-url [:map {:closed true} :field.video/format [:field.video/vimeo-url :http/url]]]
                                 [:video.format/youtube-url [:map {:closed true} :field.video/format [:field.video/youtube-url :http/url]]]
                                 [:video.format/youtube-id [:map {:closed true} :field.video/format [:field.video/youtube-id :string]]]]}
         :field.spec.option/color {s- :html/color},
         :field.spec.option/default? {s- :boolean},
         :field.spec.option/label {s- :string},
         :field.spec.option/value {s- :string},
         :field.video/format {s- [:enum :video.format/vimeo-url :video.format/youtube-url :video.format/youtube-id]},
         :field.video/url {s- :http/url},
         :field.video/youtube-id {s- :string},
         :grant/_entity {s- [:sequential :entity/grant]}
         :grant/_member {s- [:sequential :entity/grant]}
         :grant/id (merge {:doc "ID field allowing for direct lookup of permissions"
                           :todo "Replace with composite unique-identity attribute :grant/member+entity"}
                          unique-string-id)
         :grant/entity (merge {:doc "Entity to which a grant applies"}
                              (ref :one #{:board/id
                                          :collection/id
                                          :org/id
                                          :project/id})),
         :grant/member (merge {:doc "Member who is granted the roles"}
                              (ref :one #{:member/id})),
         :grant/role {:doc "A keyword representing a role which may be granted to a member",
                      s- [:enum :role/admin :role/collaborator :role/member]},
         :grant/roles (merge {:doc "Set of roles granted",
                              :db/valueType :db.type/keyword
                              s- [:sequential :grant/role]}
                             s/many),
         :html/card-classes {:doc "Classes for displaying this entity in card mode",
                             :todo "Deprecate in favour of controlled customizations"
                             s- [:vector :string]},
         :html/color {s- :string},
         :html/css {:doc "CSS styles (a string) relevant to a given entity",
                    :todo "Deprecate in favour of defined means of customization"
                    s- :string},
         :html/js {s- :string, :todo "Deprecate or otherwise restrict in hosted mode"},
         :html.meta/description {s- :string},
         :http/url {s- [:re #"https?://.+\..+"]}
         :member/board (ref :one #{:board/id})
         :member/email-frequency {s- [:enum
                                      :member.email-frequency/never
                                      :member.email-frequency/daily
                                      :member.email-frequency/periodic
                                      :member.email-frequency/instant]},
         :member/firebase-account (ref :one #{:firebase-account/id}),
         :member/id unique-string-id
         :member/image-url {s- :http/url},
         :member/name {s- :string},
         :member/new? {s- :boolean},
         :member/newsletter-subscription? {s- :boolean},
         :member/project-participant? {:doc "Member has the intention of participating in a project"
                                       s- :boolean},
         :member/tags (ref :many #{:tag/id})
         :member.admin/inactive? {:doc "Marks a member inactive, hidden.",
                                  :todo "If an inactive member signs in to a board, mark as active again."
                                  s- :boolean},
         :member.admin/suspected-fake? {s- :boolean},
         :notification/comment (ref :one #{:post.comment/id}),
         :notification/discussion (ref :one #{:discussion/id})
         :notification/emailed? {:doc "The notification has been included in an email",
                                 :todo "deprecate: log {:notifications/emailed-at _} per member"
                                 s- :boolean},
         :notification/id unique-string-id
         :notification/member (ref :one #{:member/id})
         :notification/post (ref :one #{:post/id})
         :notification/post.comment (ref :one #{:post.comment/id})
         :notification/project (ref :one #{:project/id})
         :notification/recipient (ref :one #{:member/id}),
         :notification/subject (merge
                                (ref :one #{:thread/id :post/id :project/id})
                                {:doc "The primary entity referred to in the notification (when viewed"}),
         :notification/thread (ref :one #{:thread/id})
         :notification/viewed? {:doc "The notification is considered 'viewed' (can occur by viewing the notification or subject)",
                                :unsure "Log log {:notifications/viewed-subject-at _} per [member, subject] pair, and {:notifications/viewed-notifications-at _}, instead?"
                                s- :boolean},
         :notification/thread.message.text {s- :string}
         :notification/type {s- [:enum
                                 :notification.type/new-project-member
                                 :notification.type/new-thread-message
                                 :notification.type/new-discussion-post
                                 :notification.type/new-post-comment]}
         :org/id unique-string-id
         :org.board/show-org-tab? {:doc "When true, boards should visually link to the parent organization (in main nav)"
                                   s- :boolean},
         :org.board/template-default (merge (ref :one #{:board/id})
                                            {:doc "Default template (a board with :board/is-template? true) for new boards created within this org"}),
         :post/comments {s- [:vector :entity/post.comment]},
         :post/text-content {s- :text-content/block},
         :post/do-not-follow (merge
                              (ref :many #{:member/id})
                              {:doc "Members who should not auto-follow this post after replying to it"}),
         :post/followers (merge (ref :many #{:member/id}) {:doc "Members who should be notified upon new replies to this post"}),
         :post.comment/text {s- :string},
         :project/id unique-string-id
         :project/board (ref :one #{:board/id})
         :project/open-requests {:doc "Currently active requests for help"
                                 s- [:sequential [:map {:closed true} :request/text]]},
         :project/summary-text {:doc "Short description of project suitable for card or <head> meta"
                                s- :string},
         :project/viable-team? {:doc "Project has sufficient members to proceed"
                                s- :boolean},
         :project/video {:doc "Primary video for project (distinct from fields)"
                         s- :field.video/entry},
         :project.admin/approved? {:doc "Set by an admin when :action/project.approve policy is present. Unapproved projects are hidden."
                                   s- :boolean},
         :project.admin/badges {:doc "A badge is displayed on a project with similar formatting to a tag. Badges are ad-hoc, not defined at a higher level.",
                                s- [:vector :content/badge]},
         :project.admin/board.number {:doc "Number assigned to a project by its board (stored as text because may contain annotations)",
                                      :todo "This could be stored in the board entity, a map of {project, number}, so that projects may participate in multiple boards"
                                      s- :string},
         :project.admin/description-content {:doc "A description field only writable by an admin"
                                             s- :text-content/block},
         :project.admin/inactive? {:doc "Marks a project inactive, hidden."
                                   s- :boolean},
         :project.admin/sticky? {:doc "Show project with border at top of project list"
                                 s- :boolean},
         :request/text {:doc "Free text description of the request"
                        s- :string},
         :slack.app/bot-token {s- :string},
         :slack.app/bot-user-id {s- :string},
         :slack.app/id unique-string-id
         :slack.broadcast/id unique-string-id
         :slack.broadcast/response-channel (ref :one #{:slack.channel/id}),
         :slack.broadcast/response-thread (ref :one #{:slack.thread/id}),
         :slack.broadcast/slack.team (ref :one #{:slack.team/id})
         :slack.broadcast/text {s- :string}

         :slack.broadcast.reply/id unique-string-id
         :slack.broadcast.reply/text {s- :string}
         :slack.broadcast.reply/slack.channel (ref :one #{:slack.channel/id})
         :slack.broadcast.reply/slack.user (ref :one #{:slack.user/id})
         :slack.broadcast/slack.broadcast.replies (merge s/many
                                                         s/ref
                                                         {s- [:sequential [:multi {:dispatch 'map?}
                                                                           [true :entity/slack.broadcast.reply]
                                                                           [false (lookup-ref #{:slack.broadcast.reply/id})]]]})
         :slack.broadcast/slack.user (ref :one #{:slack.user/id})
         :slack.channel/id unique-string-id
         :slack.channel/project (ref :one #{:project/id}),
         :slack.channel/slack.team (ref :one #{:slack.team/id}),
         :slack.team/board (merge (ref :one #{:board/id})
                                  {:doc "The sparkboard connected to this slack team"}),
         :slack.team/custom-messages {s- [:map {:closed true} :slack.team.custom-message/welcome],
                                      :doc "Custom messages for a Slack integration"},
         :slack.team/id unique-string-id
         :slack.team/invite-link {s- :string,
                                  :doc "Invitation link that allows a new user to sign up for a Slack team. (Typically expires every 30 days.)"},
         :slack.team/name {s- :string},
         :slack.team/slack.app {:doc "Slack app connected to this team (managed by Sparkboard)"
                                s- [:map {:closed true}
                                    :slack.app/id
                                    :slack.app/bot-user-id
                                    :slack.app/bot-token
                                    ]},
         :slack.team.custom-message/welcome {s- :string,
                                             :doc "A message sent to each user that joins the connected workspace (slack team). It should prompt the user to connect their account."},
         :slack.user/id unique-string-id
         :slack.user/firebase-account (ref :one #{:firebase-account/id}),
         :slack.user/slack.team (ref :one #{:slack.team/id}),
         :social-feed.twitter/hashtags {s- [:set :string]},
         :social-feed.twitter/mentions {s- [:set :string]},
         :social-feed.twitter/profiles {s- [:set :string]},
         :content/badge {s- [:map {:closed true} :badge/label]},
         :text-content/block {s- [:map {:closed true}
                                  :text-content/format
                                  :text-content/string]},
         :ts/created-at {s- 'inst?, :doc "Auto-generated creation-date of entity"},
         :ts/created-by (merge (ref :one #{:member/id}) {:doc "Member who created this entity"}),
         :ts/deleted-at {:doc "Deletion flag"
                         :todo "Excise deleted data after a grace period"
                         s- 'inst?},
         :http/domain (merge (ref :one #{:domain/name}) {:doc "Domain name linked to this entity"})
         :spark/event {s- [:enum
                           :event.board/update-member
                           :event.board/new-member]}
         :spark/image-urls {s- [:map-of
                                [:qualified-keyword {:namespace :image}]
                                :http/url]}
         :ts/modified-by (ref :one #{:member/id}),
         :visibility/public? {:doc "Contents of this entity can be accessed without authentication (eg. and indexed by search engines)"
                              s- :boolean},
         :social/sharing-button {s- [:enum
                                     :social.sharing-button/facebook
                                     :social.sharing-button/twitter
                                     :social.sharing-button/qr-code]},
         :social/feed {:doc "Settings for a live feed of social media related to an entity"
                       s- [:map {:closed true}
                           (? :social-feed.twitter/hashtags)
                           (? :social-feed.twitter/profiles)
                           (? :social-feed.twitter/mentions)]},
         :content/title {s- :string},
         :ts/updated-at {s- 'inst?},
         :i18n/default-locale {s- :string},
         :i18n/dictionary {s- [:map-of :string :string]}
         :i18n/extra-translations {:doc "Extra/override translations, eg. {'fr' {'hello' 'bonjour'}}",
                                   s- [:map-of :i18n/name :i18n/dictionary]},
         :i18n/name {:doc "2-letter locale name, eg. 'en', 'fr'", s- [:re #"[a-z]{2}"]},
         :i18n/suggested-locales {:doc "Suggested locales (set by admin, based on expected users)",
                                  s- [:vector :i18n/name]},
         :tag/id unique-string-id
         :tag/_managed-by {s- [:sequential :entity/tag]}
         :tag/background-color {s- :html/color},
         :tag/label {s- :string},
         :tag/managed-by (merge {:doc "The entity which manages this tag"}
                                (ref :one #{:board/id})),
         :tag/restricted? {:doc "Tag may only be modified by an admin of the owner of this tag"
                           s- :boolean}
         :thread/id unique-string-id
         :thread/members (merge (ref :many #{:member/id})
                                {:doc "Set of participants in a thread."}),
         :thread.message/id {s- :string}
         :thread.message/text {s- :string}
         :thread/messages {:doc "List of messages in a thread.",
                           :order-by :ts/created-at
                           s- [:sequential :entity/thread.message]},
         :thread/read-by (merge
                          (ref :many #{:member/id})
                          {:doc "Set of members who have read the most recent message.",
                           :todo "Map of {member, last-read-message} so that we can show unread messages for each member."})}
    (-> (update-vals :spark/schema) register!)))

(comment
 (->> extra-schema (remove (comp :spark/schema val))))

(defn entity-schema [m]
  (some {:field.spec/id :entity/field.spec
         :notification/id :entity/notification
         :thread/id :entity/thread
         :slack.user/id :entity/slack.user
         :discussion/id :entity/discussion
         :board/id :entity/board
         :slack.channel/id :entity/slack.channel
         :org/id :entity/org
         :thread.message/id :entity/thread.message
         :slack.broadcast/id :entity/slack.broadcast
         :slack.app/id :entity/slack.app
         :collection/id :entity/collection
         :grant/id :entity/grant
         :slack.team/id :entity/slack.team
         :field/id :entity/field
         :tag/id :entity/tag
         :member/id :entity/member
         :project/id :entity/project
         :domain/name :entity/domain
         :member-vote.entry/id :entity/member-vote.entry} (keys m)))

;; previously used - relates to :notification/subject-viewed?
(def notification-subjects {:notification.type/new-project-member :project/id
                            :notification.type/new-thread-message :thread/id
                            :notification.type/new-discussion-post :post/id
                            :notification.type/new-post-comment :post/id})


(comment
 :board.registration/steps [:sequential
                            [:map
                             :registration.step/title
                             :registration.step/is-complete-fn
                             ]]
 :registration.step/is-complete-fn

 )
