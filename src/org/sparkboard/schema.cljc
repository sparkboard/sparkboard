(ns org.sparkboard.schema
  (:refer-clojure :exclude [ref])
  (:require [re-db.schema :as s]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.experimental.lite :as l]
            [malli.registry :as mr]
            [malli.provider :as mp]
            [re-db.in-memory :as mem]
            [re-db.api :as d]))

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

(gen [:map [:image-url/logo {:optional true}]])

(gen :db/lookup-ref)

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

(defn ref
  ([cardinality] (case cardinality :one (merge s/ref
                                               s/one
                                               {s- :db/lookup-ref})
                                   :many (merge s/ref
                                                s/many
                                                {s- [:sequential :db/lookup-ref]})))
  ([cardinality ks]
   (let [lookup-ref [:tuple (into [:enum] ks) 'any?]]
     (case cardinality :one (merge s/ref
                                   s/one
                                   {s- lookup-ref})
                       :many (merge s/ref
                                    s/many
                                    {s- [:sequential lookup-ref]})))))

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
                         s- [:map {:closed true} :policy/requires-role]},
         :action/project.approve {:doc "The act of approving a project, which makes it visible to other members."
                                  :db/enum-value? true},
         :action/project.create {:doc "The act of creating a new project."
                                 :db/enum-value? true},
         :policy/requires-role {:doc "Policy: the action may only be completed by a member who has one of the provided roles."
                                s- [:set :grant/role]},
         :badge/label {s- :string
                       :spark/user-text true},
         :board/is-template? {:doc "Board is only used as a template for creating other boards",
                              s- :boolean},
         :board/labels {:unsure "How can this be handled w.r.t. locale?"
                        s- [:map-of [:enum
                                     :label/member.one
                                     :label/member.many
                                     :label/project.one
                                     :label/project.many] :string]},
         :board/policies {s- [:map-of [:qualified-keyword {:namespace :action}] :action/policy]},
         :board.landing-page/description-content {:doc "Primary description of a board, displayed in header"
                                                  s- :spark/content}
         :board.landing-page/instruction-content {:doc "Secondary instructions for a board, displayed above projects"
                                                  s- :spark/content},
         :board.landing-page/learn-more-url {:doc ""
                                             s- :http/url},
         :board.member/fields {s- [:sequential :entity/field.spec]}
         :board.member/max-projects {:doc "Set a maximum number of projects a member may join"
                                     s- :int},
         :board.project/fields {s- [:sequential :entity/field.spec]}
         :board.project/max-members {:doc "Set a maximum number of members a project may have"
                                     s- :int},
         :board.member/private-messaging? {:doc "Enable the private messaging feature for this board"
                                           s- :boolean},
         :board.member/tags {s- [:sequential :entity/tag]}
         :board.project/sharing-buttons {:doc "Which social sharing buttons to display on project detail pages",
                                         s- [:map-of :spark/sharing-button :boolean]},
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
                                                       s- :spark/content},
         :board.registration/register-at-url {:doc "URL to redirect user for registration (replaces the Sparkboard registration page, admins are expected to invite users)",
                                              s- :http/url},
         :collection/boards (ref :many #{:board/id})
         :community-vote.entry/_member {s- :entity/community-vote.entry}
         :community-vote.entry/board (ref :one #{:board/id})
         :community-vote.entry/member (ref :one #{:member/id})
         :community-vote.entry/project (ref :one #{:project/id})

         :community-vote/_board {s- [:sequential :entity/community-vote]}
         :community-vote/board (ref :one #{:board/id})
         :community-vote/open? {:doc "Opens a community vote (shown as a tab on the board)"
                                s- :boolean},

         :content/format {s- [:enum :html :markdown]},
         :content/text {s- :string},
         :db/lookup-ref {s- [:tuple :qualified-keyword :string]},
         :discussion/followers (ref :many #{:member/id}),
         :domain/entity (merge (ref :one #{:board/id :org/id :collection/id})
                               {:doc "The entity this domain points to",}),
         :domain/name {:doc "A complete domain name, eg a.b.com",
                       s- :string},
         :entity/board {s- [:map {:closed true}
                            (? :board/image-urls)
                            (? :board/is-template?)
                            (? :board/labels)
                            (? :board/policies)
                            (? :board/slack.team)
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
                            (? :board.registration/newsletter-subscription-field?)
                            (? :board.registration/register-at-url)
                            (? :html/css)
                            (? :html/js)
                            (? :html.meta/description)
                            (? :spark/social-feed)
                            (? :tag/_managed-by)
                            :board/id
                            :board/org
                            :board.member/private-messaging?
                            :board.registration/open?
                            :community-vote/_board
                            :spark/created-at
                            :spark/domain
                            :spark/public?
                            :spark/title
                            :spark.locales/default
                            (? :spark.locales/suggested)]},
         :entity/collection {s- [:map {:closed true}
                                 :collection/boards
                                 :collection/id
                                 :collection/image-urls
                                 :spark/domain
                                 :spark/title]}
         :entity/community-vote.entry {s- [:map {:closed true}
                                           :community-vote.entry/board
                                           :community-vote.entry/member
                                           :community-vote.entry/project]}
         :entity/community-vote {s- [:map {:closed true}
                                     :community-vote/board
                                     :community-vote/open?]},
         :entity/discussion {s- [:map {:closed true}
                                 :discussion/board
                                 :discussion/followers
                                 :discussion/id
                                 :discussion/posts
                                 :discussion/project
                                 :spark/created-at]},
         :entity/domain {s- [:map {:closed true}
                             :domain/entity
                             :domain/name]},
         :entity/field {s- [:map {:closed true}
                            :field/field.spec
                            :field.spec/type
                            :field/id
                            :field/parent]}
         :entity/field.spec {:doc "Description of a field."
                             :todo ["Field specs should be definable at a global, org or board level."
                                    "Orgs/boards should be able to override/add field.spec options."
                                    "Field specs should be globally merged so that fields representing the 'same' thing can be globally searched/filtered?"]
                             s- [:map {:closed true}
                                 (? :field.spec/hint)
                                 (? :field.spec/options)
                                 (? :field.spec/required?)
                                 (? :field.spec/show-as-filter?)
                                 (? :field.spec/show-at-create?)
                                 (? :field.spec/show-on-card?)
                                 :field.spec/id
                                 :field.spec/label
                                 :field.spec/managed-by
                                 :field.spec/order
                                 :field.spec/type]}
         :entity/grant {s- [:map {:closed true}
                            :grant/id
                            :grant/roles
                            :grant/entity
                            :grant/member]}
         :entity/member {s- [:map {:closed true}
                             (? :community-vote.entry/_member)
                             (? :field/_target)
                             (? :member/image-url)
                             (? :member/newsletter-subscription?)
                             (? :member.admin/suspected-fake?)
                             :grant/_member
                             :member/board
                             :member/email-frequency
                             :member/firebase-account
                             :member/id
                             :member/name
                             :member/new?
                             :member/project-participant?
                             :member/tags
                             :member.admin/inactive?
                             :spark/created-at
                             :spark/deleted?
                             :spark/updated-at]},
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
                                   :spark/created-at]},
         :entity/org {s- [:map {:closed true}
                          (? :org.board/show-org-tab?)
                          (? :org/image-urls)
                          (? :org.board/template-default)
                          (? :spark/social-feed)
                          (? :spark.locales/suggested)
                          :org/id
                          :spark/created-by
                          :spark/domain
                          :spark/public?
                          :spark/title]},
         :entity/post.comment {s- [:map {:closed true}
                                   :spark/created-at
                                   :spark/created-by
                                   :post.comment/id
                                   :post.comment/text]},
         :entity/project {s- [:map {:closed true}
                              (? :spark/last-modified-by)
                              (? :field/_target)
                              (? :html/card-classes)
                              (? :project/open-requests)
                              (? :project/summary-text)
                              (? :project/viable-team?)
                              (? :project/video-url)
                              (? :project.admin/approved?)
                              (? :project.admin/badges)
                              (? :project.admin/board.number)
                              (? :project.admin/description)
                              (? :project.admin/sticky?)
                              (? :project.admin/inactive?)
                              :grant/_entity
                              :project/board
                              :project/id
                              :spark/created-at
                              :spark/created-by
                              :spark/deleted?
                              :spark/title
                              :spark/updated-at]},
         :entity/slack.broadcast {s- [:map {:closed true}
                                      (? :slack.broadcast/response-channel)
                                      (? :slack.broadcast/response-thread)
                                      (? :slack.broadcast/slack.broadcast.replies)
                                      :slack.broadcast/id
                                      :slack.broadcast/slack.team
                                      :slack.broadcast/slack.user
                                      :slack.broadcast/text]},
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
                          (? :tag/locked)
                          :tag/label
                          :tag/id
                          :tag/managed-by]},
         :entity/thread {s- [:map {:closed true}
                             :spark/created-at
                             :spark/updated-at
                             :thread/id
                             :thread/members
                             :thread/messages
                             :thread/read-by]},
         :entity/thread.message {s- [:map {:closed true}
                                     :thread.message/id
                                     :thread.message/text
                                     :spark/created-by
                                     :spark/created-at]}
         :field/_target {s- :entity/field}
         :field/id (merge unique-string-id
                          {:todo "Should be a tuple field of [:field/parent :field/spec] ?"}),
         :field/parent (ref :one #{:member/id :project/id}),
         :field/field.spec (ref :one #{:field.spec/id}),
         :field.image/image-url {s- :http/url},
         :field.link-list/text {s- :string},
         :field.link-list/url {s- :http/url},
         :field.select/value {s- :string},
         :field.spec/hint {s- :string},
         :field.spec/id {s- :string}
         :field.spec/label {s- :string},
         :field.spec/managed-by (ref :one #{:board/id :org/id}),
         :field.spec/options {s- (? [:sequential :field.spec/option])},
         :field.spec/option {s- [:map {:closed true}
                                 :field.spec.option/label
                                 :field.spec.option/value
                                 :field.spec.option/color
                                 :field.spec.option/default?]}
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
                               :field.type/content
                               :field.type/content]},
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
         :grant/entity (merge {:doc "Entity to which a grant applies"} (ref :one #{:board/id :org/id :project/id})),
         :grant/member (merge {:doc "Member who is granted the roles"} (ref :one #{:member/id})),
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
         :member/email-frequency {s- [:enum :daily :instant :periodic :never]},
         :member/firebase-account (ref :one #{:firebase-account/id}),
         :member/image-url {s- :http/url},
         :member/name {s- :string},
         :member/new? {s- :boolean},
         :member/newsletter-subscription? {s- :boolean},
         :member/project-participant? {:doc "Member has the intention of participating in a project"
                                       s- :boolean},
         :member.admin/inactive? {:doc "Marks a member inactive, hidden.",
                                  :todo "If an inactive member signs in to a board, mark as active again."
                                  s- :boolean},
         :member.admin/suspected-fake? {s- :boolean},
         :notification/comment (ref :one #{:post.comment/id}),
         :notification/emailed? {:doc "The notification has been included in an email",
                                 :todo "deprecate: log {:notifications/emailed-at _} per member"
                                 s- :boolean},
         :notification/recipient (ref :one #{:member/id}),
         :notification/subject (merge
                                (ref :one #{:thread/id :post/id :project/id})
                                {:doc "The primary entity referred to in the notification (when viewed"}),
         :notification/viewed? {:doc "The notification is considered 'viewed' (can occur by viewing the notification or subject)",
                                :unsure "Log log {:notifications/viewed-subject-at _} per [member, subject] pair, and {:notifications/viewed-notifications-at _}, instead?"
                                s- :boolean},
         :notification/thread.message.text {s- :string}
         :notification/type {s- [:enum
                                 :notification.type/new-project-member
                                 :notification.type/new-thread-message
                                 :notification.type/new-discussion-post
                                 :notification.type/new-post-comment]},,
         :org.board/show-org-tab? {:doc "When true, boards should visually link to the parent organization (in main nav)"
                                   s- :boolean},
         :org.board/template-default (merge (ref :one #{:board/id})
                                            {:doc "Default template (a board with :board/is-template? true) for new boards created within this org"}),
         :post/comments {s- [:vector :entity/post.comment]},
         :post/content {s- :spark/content},
         :post/do-not-follow (merge
                              (ref :many #{:member/id})
                              {:doc "Members who should not auto-follow this post after replying to it"}),
         :post/followers (merge (ref :many #{:member/id}) {:doc "Members who should be notified upon new replies to this post"}),
         :post.comment/text {s- :string},
         :project/open-requests {:doc "Currently active requests for help"
                                 s- [:sequential [:map :request/text]]},
         :project/summary-text {:doc "Short description of project suitable for card or <head> meta"
                                s- :string},
         :project/viable-team? {:doc "Project has sufficient members to proceed"
                                s- :boolean},
         :project/video-url {:doc "Primary video for project (distinct from fields)"
                             s- :http/url},
         :project.admin/approved? {:doc "Set by an admin when :action/project.approve policy is present. Unapproved projects are hidden."
                                   s- :boolean},
         :project.admin/badges {:doc "A badge is displayed on a project with similar formatting to a tag. Badges are ad-hoc, not defined at a higher level.",
                                s- [:vector :spark/badge]},
         :project.admin/board.number {:doc "Number assigned to a project by its board (stored as text because may contain annotations)",
                                      :todo "This could be stored in the board entity, a map of {project, number}, so that projects may participate in multiple boards"
                                      s- :string},
         :project.admin/description-content {:doc "A description field only writable by an admin"
                                             s- :spark/content},
         :project.admin/inactive? {:doc "Marks a project inactive, hidden."
                                   s- :boolean},
         :project.admin/sticky? {:doc "Show project with border at top of project list"
                                 s- :boolean},
         :request/text {:doc "Free text description of the request"
                        s- :string},
         :sharing-button/facebook {:db/enum-value? true}
         :sharing-button/qr-code {:db/enum-value? true}
         :sharing-button/twitter {:db/enum-value? true}
         :slack-user/firebase-account (ref :one #{:firebase-account/id}),
         :slack.app/bot-token {s- :string},
         :slack.app/bot-user-id {s- :string},
         :slack.broadcast/response-channel (ref :one #{:slack.channel/id}),
         :slack.broadcast/response-thread (ref :one #{:slack.thread/id}),
         :slack.broadcast/text {s- :string}
         :slack.broadcast.reply/slack.user (ref :one #{:slack.user/id})
         :slack.broadcast.reply/text {s- :string}
         :slack.channel/project (ref :one #{:project/id}),
         :slack.channel/slack.team (ref :one #{:slack.team/id}),
         :slack.team/board (merge (ref :one #{:board/id})
                                  {:doc "The sparkboard connected to this slack team"}),
         :slack.team/custom-messages {s- [:map :slack.team.custom-message/welcome],
                                      :doc "Custom messages for a Slack integration"},
         :slack.team/invite-link {s- :string,
                                  :doc "Invitation link that allows a new user to sign up for a Slack team. (Typically expires every 30 days.)"},
         :slack.team/name {s- :string},
         :slack.team/slack.app {:doc "Slack app connected to this team (managed by Sparkboard)"
                                s- [:map
                                    :slack.app/id
                                    :slack.app/bot-user-id
                                    :slack.app/bot-token
                                    ]},
         :slack.team.custom-message/welcome {s- :string,
                                             :doc "A message sent to each user that joins the connected workspace (slack team). It should prompt the user to connect their account."},
         :slack.user/firebase-account (ref :one #{:firebase-account/id}),
         :slack.user/slack.team (ref :one #{:slack.team/id}),
         :social-feed.twitter/hashtags {s- [:set :string]},
         :social-feed.twitter/mentions {s- [:set :string]},
         :social-feed.twitter/profiles {s- [:set :string]},
         :spark/badge {s- [:map :badge/label]},
         :spark/content {s- [:map :content/format :content/text]},
         :spark/created-at {s- 'inst?, :doc "Auto-generated creation-date of entity"},
         :spark/created-by (merge (ref :one #{:member/id}) {:doc "Member who created this entity"}),
         :spark/deleted? {:doc "Deletion flag"
                          :todo "Excise deleted data after a grace period"
                          s- :boolean},
         :spark/domain (merge (ref :one #{:domain/name}) {:doc "Domain name linked to this entity"}),,
         :spark/last-modified-by (ref :one #{:member/id}),
         :spark/public? {:doc "Contents of this entity can be accessed without authentication (eg. and indexed by search engines)"
                         s- :boolean},
         :spark/sharing-button {s- [:enum :sharing-button/facebook :sharing-button/twitter :sharing-button/qr-code]},
         :spark/social-feed {:doc "Settings for a live feed of social media related to an entity"
                             s- [:map
                                 (? :social-feed.twitter/hashtags)
                                 (? :social-feed.twitter/profiles)
                                 (? :social-feed.twitter/mentions)]},
         :spark/title {s- :string},
         :spark/updated-at {s- 'inst?},
         :spark.locales/default {s- :string},
         :spark.locales/dictionary {:doc "Extra/override translations, eg. {'fr' {'hello' 'bonjour'}}",
                                    s- [:map-of :spark.locales/name :spark.locale/dictionary]},
         :spark.locales/name {:doc "2-letter locale name, eg. 'en', 'fr'", s- [:re #"[a-z]{2}"]},
         :spark.locales/suggested {:doc "Suggested locales (set by admin, based on expected users)",
                                   s- [:vector :spark.locales/name]},
         :tag/_managed-by {s- [:sequential :entity/tag]}
         :tag/background-color {s- :html/color},
         :tag/label {s- :string},
         :tag/managed-by (merge {:doc "The entity which manages this tag"}
                                (ref :one #{:board/id})),
         :tag/restricted? {:doc "Tag may only be modified by an admin of the owner of this tag"
                           s- :boolean},
         :thread/members (merge (ref :many #{:member/id})
                                {:doc "Set of participants in a thread."}),
         :thread.message/id {s- :string}
         :thread.message/text {s- :string}
         :thread/messages {:doc "List of messages in a thread.",
                           :order-by :spark/created-at
                           s- [:sequential :entity/thread.message]},
         :thread/read-by (merge
                          (ref :many #{:member/id})
                          {:doc "Set of members who have read the most recent message.",
                           :todo "Map of {member, last-read-message} so that we can show unread messages for each member."}),}
    (-> (update-vals :spark/schema) register!)))

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
