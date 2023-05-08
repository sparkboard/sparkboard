(ns sparkboard.schema
  (:refer-clojure :exclude [ref])
  (:require [clojure.string :as str]
            [malli.core :as m]
            [sparkboard.impl.schema :refer :all]
            [malli.error :refer [humanize]]
            [malli.registry :as mr]
            [malli.util :as mu]
            [re-db.schema :as s]
            [re-db.api :as db]
            [sparkboard.util :as u]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; For bulk data import

(def !registry (atom (m/default-schemas)))
(mr/set-default-registry! (mr/mutable-registry !registry))

(def sb-badges
  {:content/badge {s- [:map {:closed true} :badge/label]}
   :badge/label {s- :string
                 :spark/user-text true}})

(def sb-board
  {:board/show-project-numbers? {:doc "Show 'project numbers' for this board"
                                 s- :boolean}
   :board/max-members-per-project {:doc "Set a maximum number of members a project may have"
                                   s- :int}
   :board/project-sharing-buttons {:doc "Which social sharing buttons to display on project detail pages",
                                   s- [:map-of :social/sharing-button :boolean]}
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
   :board/title {s- :string
                 :db/fulltext true}
   :board/description {:doc "Primary description of a board, displayed in header"
                       s- :text-content/block
                       :db/fulltext true}
   :board/instructions {:doc "Secondary instructions for a board, displayed above projects"
                        s- :text-content/block
                        :db/fulltext true},
   :board/learn-more-url {:doc ""
                          s- :http/url}
   :board/max-projects-per-member {:doc "Set a maximum number of projects a member may join"
                                   s- :int}
   :board/sticky-color {:doc "Border color for sticky projects", s- :html/color}
   :board/member-tags (ref :many #{:tag/id})
   :board/project-fields (merge (ref :many #{:field-spec/id})
                                {s- [:sequential :field-spec/as-map]})
   :board/images {s- :image-urls/as-map}
   :board/social-feed {s- :social/feed}
   :board/member-fields {s- [:sequential :field-spec/as-map]}
   :board/registration-invitation-email-text {:doc "Body of email sent when inviting a user to a board."
                                              s- :string},
   :board/registration-newsletter-field? {:doc "During registration, request permission to send the user an email newsletter"
                                          s- :boolean},
   :board/registration-open? {:doc "Allows new registrations via the registration page. Does not affect invitations.",
                              s- :boolean},
   :board/registration-message-content {:doc "Content displayed on registration screen (before user chooses provider / enters email)"
                                        s- :text-content/block},
   :board/registration-url-override {:doc "URL to redirect user for registration (replaces the Sparkboard registration page, admins are expected to invite users)",
                                     s- :http/url},
   :board/registration-codes {s- [:map-of :string [:map {:closed true} [:registration-code/active? :boolean]]]}
   :board/rules {s- [:map-of
                     [:qualified-keyword {:namespace :action}]
                     [:map
                      [:policy/requires-role [:set :membership.role]]]]}
   :board/member-vote-open? {:doc "Opens a community vote (shown as a tab on the board)"
                             s- :boolean}
   :board/custom-css {:doc "Custom CSS for this board"
                      s- :string}
   :board/custom-js {:doc "Custom JS for this board"
                     s- :string}
   :board/custom-meta-description {:doc "Custom meta description for this board"
                                   s- :string}
   :board/locale-default {s- :i18n/locale}
   :board/locale-suggestions {s- :i18n/locale-suggestions}
   :board/locale-dicts {s- :i18n/locale-dicts}
   :board/as-map {s- [:map {:closed true}
                      :sb/id
                      :board/id
                      :board/org
                      :board/title
                      :board/registration-open?
                      :ts/created-at
                      :visibility/public?
                      (? :board/custom-css)
                      (? :board/custom-js)
                      (? :board/custom-meta-description)
                      (? :board/description)
                      (? :board/instructions)
                      (? :board/is-template?)
                      (? :board/labels)
                      (? :board/learn-more-url)
                      (? :board/max-members-per-project)
                      (? :board/max-projects-per-member)
                      (? :board/member-fields)
                      (? :board/member-tags)
                      (? :board/member-vote-open?)
                      (? :board/rules)
                      (? :board/project-fields)
                      (? :board/project-sharing-buttons)
                      (? :board/registration-codes)
                      (? :board/registration-invitation-email-text)
                      (? :board/registration-message-content)
                      (? :board/registration-newsletter-field?)
                      (? :board/registration-url-override)
                      (? :board/show-project-numbers?)
                      (? :board/slack.team)
                      (? :board/sticky-color)
                      (? :entity/domain)
                      (? :board/locale-default)
                      (? :board/locale-dicts)
                      (? :board/locale-suggestions)
                      (? :board/images)
                      (? :board/social-feed)
                      (? :ts/deleted-at)
                      (? :webhook/subscriptions)]}})

(def sb-collections
  {:collection/id unique-string-id
   :collection/boards (ref :many #{:board/id})
   :collection/title {s- :string}
   :collection/images {s- :image-urls/as-map}
   :collection/as-map {s- [:map {:closed true}
                           :sb/id
                           :collection/images
                           :collection/boards
                           :collection/id
                           :collection/title
                           :entity/domain]}})

(def sb-discussion
  {:discussion/id unique-string-id
   :discussion/followers (ref :many #{:member/id}),
   :discussion/posts (merge (ref :many #{:post/id})
                            s/component)
   :discussion/project (ref :one #{:project/id})
   :discussion/as-map {s- [:map {:closed true}
                           :sb/id
                           :discussion/id
                           :discussion/project
                           :ts/created-at
                           (? :discussion/followers)
                           (? :discussion/posts)]}})

(def sb-domains
  {:domain/url {s- :http/url}
   :domain/target-type {s- [:enum
                            :domain/url
                            :domain/entity]}
   :domain/entity (merge (ref :one #{:board/id :org/id :collection/id})
                         {:doc "The entity this domain points to",})
   :domain/name (merge {:doc "A complete domain name, eg a.b.com",}
                       unique-string-id)
   :entity/domain (merge (ref :one #{:domain/name}) {:doc "Domain name linked to this entity"})
   :domain/as-map (merge (ref :one #{:domain/name})
                         {s- [:multi {:dispatch :domain/target-type}
                              [:domain/url
                               [:map {:closed true}
                                [:domain/target-type [:= :domain/url]]
                                :domain/url
                                :domain/name]]
                              [:domain/entity
                               [:map {:closed true}
                                [:domain/target-type [:= :domain/entity]]
                                :domain/entity
                                :domain/name]]]})})

(def sb-fields
  {:field/id (merge unique-string-id
                    {:todo "Should be a tuple field of [:field/parent :field/spec] ?"}),
   :field/parent (ref :one #{:member/id :project/id}),
   :field/field-spec (ref :one #{:field-spec/id}),
   :link-list/link {s- [:map {:closed true}
                        [:text :string]
                        [:url :http/url]]}
   :field-spec/hint {s- :string},
   :field-spec/id unique-string-id
   :field-spec/label {s- :string},
   :field-spec/managed-by (ref :one #{:board/id :org/id}),
   :field-spec/options {s- (? [:sequential :field.spec/option])},
   :field.spec/option {s- [:map {:closed true}
                           (? :option/color)
                           (? :option/default?)
                           (? :option/value)
                           :option/label]}
   :field-spec/order {s- :int},
   :field-spec/required? {s- :boolean},
   :field-spec/show-as-filter? {:doc "Use this field as a filtering option"
                                s- :boolean},
   :field-spec/show-at-create? {:doc "Ask for this field when creating a new entity"
                                s- :boolean},
   :field-spec/show-on-card? {:doc "Show this field on the entity when viewed as a card"
                              s- :boolean},
   :field/type {s- [:enum
                    :field.type/image
                    :field.type/video
                    :field.type/select
                    :field.type/link-list
                    :field.type/text-content
                    :field.type/text-content]}
   :field/value {s- [:multi {:dispatch :field/type}
                     [:field.type/image
                      [:map {:closed true}
                       [:image/url :http/url]
                       [:field/type [:= :field.type/image]]]]
                     [:field.type/link-list
                      [:map {:closed true}
                       [:link-list/items
                        [:sequential :link-list/link]]
                       [:field/type [:= :field.type/link-list]]]]
                     [:field.type/select
                      [:map {:closed true}
                       [:select/value :string]
                       [:field/type [:= :field.type/select]]]]
                     [:field.type/text-content
                      [:map {:closed true}
                       :text-content/block
                       [:db/fulltext [:= true]]
                       [:field/type [:= :field.type/text-content]]]]
                     [:field.type/video
                      [:map {:closed true}
                       :video/value
                       [:field/type [:= :field.type/video]]]]]}
   :video/value {s- [:multi {:dispatch 'first}
                     [:video/vimeo-url [:tuple [:= :video/vimeo-url] :http/url]]
                     [:video/youtube-url [:tuple [:= :video/youtube-url] :http/url]]
                     [:video/youtube-id [:tuple [:= :video/youtube-id] :string]]]}
   :option/color {s- :html/color},
   :option/default? {s- :boolean},
   :option/label {s- :string},
   :option/value {s- :string},
   :video/youtube-id {s- :string}
   :field/as-map {s- [:map {:closed true}
                      :field/field-spec
                      :field/id
                      :field/value]}
   :field-spec/as-map {:doc "Description of a field."
                       :todo ["Field specs should be definable at a global, org or board level."
                              "Orgs/boards should be able to override/add field.spec options."
                              "Field specs should be globally merged so that fields representing the 'same' thing can be globally searched/filtered?"]
                       s- [:map {:closed true}
                           :sb/id
                           :field-spec/id
                           :field-spec/managed-by
                           :field-spec/order
                           :field/type
                           (? :field-spec/hint)
                           (? :field-spec/label)
                           (? :field-spec/options)
                           (? :field-spec/required?)
                           (? :field-spec/show-as-filter?)
                           (? :field-spec/show-at-create?)
                           (? :field-spec/show-on-card?)]}})

(def sb-firebase-account
  {:account/id unique-string-id
   :account/email unique-string-id
   :account/email-verified? {s- :boolean}
   :account/display-name {s- :string}
   :account.provider.google/sub unique-string-id
   :account/last-sign-in {s- 'inst?}
   :account/password-hash {s- :string}
   :account/password-salt {s- :string}
   :account/photo-url {s- :http/url}
   :account/locale {s- :i18n/locale}
   :account/as-map {s- [:map {:closed true}
                        :sb/id
                        :account/id
                        :account/email
                        :account/email-verified?
                        :ts/created-at
                        (? :account/locale)
                        (? :account/last-sign-in)
                        (? :account/display-name)
                        (? :account/password-hash)
                        (? :account/password-salt)
                        (? :account/photo-url)
                        (? :account.provider.google/sub)]}})

;; validation for endpoints
;; - annotate endpoint functions with malli schema for :in and :out
;; - we need nice error messages

;; malli validator for a string with minimum length 8
;; malli email validator

;; http error code for invalid input
;; 400 bad request

(humanize
 (m/explain
  [:map
   [:x
    [:map
     [:y :int]]]]
  {:x {:y "foo"}}))

(def sb-memberships
  {:membership/_entity {s- [:sequential :membership/as-map]}
   :membership/_member {s- [:sequential :membership/as-map]}
   :membership/id (merge {:doc "ID field allowing for direct lookup of permissions"
                          :todo "Replace with composite unique-identity attribute :grant/member+entity"}
                         unique-string-id)
   :membership/entity (merge {:doc "Entity to which a grant applies"}
                             (ref :one #{:board/id
                                         :collection/id
                                         :org/id
                                         :project/id}))
   :membership/account (ref :one #{:account/id})
   :membership/member (merge {:doc "Member who is granted the roles"}
                             (ref :one #{:member/id})),
   :membership.role {:doc "A keyword representing a role which may be granted to a member",
                     s- [:enum :role/admin :role/collaborator :role/member]},
   :membership/roles (merge {:doc "Set of roles granted",
                             s- [:set :membership.role]}
                            s/keyword
                            s/many)
   :membership/as-map {s- [:and [:map {:closed true}
                                 :sb/id
                                 :membership/id
                                 :membership/roles
                                 :membership/entity
                                 (? :membership/member)
                                 (? :membership/account)]
                           [:fn {:error/message "Membership must contain :membership/member or :membership/account"}
                            '(fn [{:keys [:membership/member :membership/account]}]
                               (and (or member account)
                                    (not (and member account))))]]}})

(def sb-util
  {:visibility/public? {:doc "Contents of this entity can be accessed without authentication (eg. and indexed by search engines)"
                        s- :boolean}
   :http/url {s- [:re #"https?://.+\..+"]},
   :html/color {s- :string}
   :email {s- [:re {:error/message "should be a valid email"} #"^[^@]+@[^@]+$"]}})

(def sb-i18n
  {:i18n/locale {:doc "ISO 639-2 language code (eg. 'en')"
                 s- [:re #"[a-z]{2}"]}
   :i18n/default-locale {s- :string},
   :i18n/locale-suggestions {:doc "Suggested locales (set by admin, based on expected users)",
                             s- [:vector :i18n/locale]}
   :i18n/dict {s- [:map-of :string :string]}
   :i18n/locale-dicts {:doc "Extra/override translations, eg. {'fr' {'hello' 'bonjour'}}",
                       s- [:map-of :i18n/locale :i18n/dict]}})

(def sb-images
  {:image-urls/as-map {s- [:map-of
                           [:qualified-keyword {:namespace :image}]
                           :http/url]}})

(def sb-member
  {:member/fields (merge (ref :many #{:field/id})
                         s/component)
   :member/name {s- :string},
   :member/new? {s- :boolean},
   :member/newsletter-subscription? {s- :boolean},
   :member/project-participant? {:doc "Member has the intention of participating in a project"
                                 s- :boolean}
   :member/tags (ref :many #{:tag/id})
   :member/tags.custom {s- [:sequential :tag/ad-hoc]}
   :member/inactive? {:doc "Marks a member inactive, hidden."
                      :admin true
                      :todo "If an inactive member signs in to a board, mark as active again?"
                      s- :boolean},
   :member/board (ref :one #{:board/id})
   :member/email-frequency {s- [:enum
                                :member.email-frequency/never
                                :member.email-frequency/daily
                                :member.email-frequency/periodic
                                :member.email-frequency/instant]}
   :member/account (ref :one #{:account/id})
   :member/id unique-string-id
   :member/image-url {s- :http/url},
   :member/as-map {s- [:map {:closed true}
                       :sb/id
                       :member/inactive?
                       :member/board
                       :member/email-frequency
                       :member/account
                       :member/id
                       :member/name
                       :member/new?
                       :member/project-participant?
                       :ts/created-at
                       :ts/updated-at
                       (? :membership/_member)
                       (? :member/fields)
                       (? :member/tags.custom)
                       (? :member/image-url)
                       (? :member/newsletter-subscription?)
                       (? :member/tags)
                       (? :ts/deleted-at)
                       (? :ts/modified-by)]}})

(def sb-member-vote
  {:ballot/board (ref :one #{:board/id})
   :ballot/id (merge {:doc "ID composed of board-id:member-id"
                      :todo "Replace with tuple attribute of board+member?"}
                     unique-string-id)
   :ballot/member (ref :one #{:member/id})
   :ballot/project (ref :one #{:project/id})
   :member-vote/ballot {s- [:map {:closed true}
                            :ballot/id
                            :ballot/board
                            :ballot/member
                            :ballot/project]}})

(def sb-notification
  {:notification/comment (ref :one #{:comment/id}),
   :notification/discussion (ref :one #{:discussion/id})
   :notification/emailed? {:doc "The notification has been included in an email",
                           :todo "deprecate: log {:notifications/emailed-at _} per member"
                           s- :boolean},
   :notification/id unique-string-id
   :notification/member (ref :one #{:member/id})
   :notification/post (ref :one #{:post/id})
   :notification/post.comment (ref :one #{:comment/id})
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
   :notification/as-map {s- [:map {:closed true}
                             :sb/id
                             :notification/emailed?
                             :notification/id
                             :notification/recipient
                             :notification/type
                             :notification/viewed?
                             :ts/created-at
                             (? :notification/discussion)
                             (? :notification/member)
                             (? :notification/post)
                             (? :notification/post.comment)
                             (? :notification/project)
                             (? :notification/thread)
                             (? :notification/thread.message.text)]}})

(def sb-org
  {:board/show-org-tab? {:doc "When true, boards should visually link to the parent organization (in main nav)"
                         s- :boolean}
   :org/id unique-string-id
   :org/image-urls {s- :image-urls/as-map}
   :org/title {s- :string}
   :org/default-board-template (merge {:doc "Default template (a board with :board/is-template? true) for new boards created within this org"}
                                      (ref :one #{:board/id}))
   :org/social-feed {s- :social/feed}
   :org/images {s- :image-urls/as-map}
   :org/locale-default {s- :i18n/locale}
   :org/locale-suggestions {s- :i18n/locale-suggestions}
   :org/as-map {s- [:map {:closed true}
                    :sb/id
                    :org/title
                    :org/id
                    :ts/created-by
                    (? :ts/created-at)
                    (? :board/show-org-tab?)
                    (? :org/default-board-template)
                    (? :org/social-feed)
                    (? :org/locale-default)
                    (? :org/locale-suggestions)
                    (? :org/images)
                    (? :entity/domain)
                    (? :visibility/public?)]}})

(def sb-posts
  {:post/_discussion {s- [:sequential :post/as-map]}
   :post/id unique-string-id
   :post/comments (merge (ref :many #{:comment/id})
                         s/component)
   :post/text-content {s- :text-content/block
                       :db/fulltext true}
   :post/do-not-follow (merge
                        {:doc "Members who should not auto-follow this post after replying to it"}
                        (ref :many #{:member/id}))
   :post/followers (merge {:doc "Members who should be notified upon new replies to this post"}
                          (ref :many #{:member/id}))
   :comment/id unique-string-id
   :comment/text {s- :string}
   :post/as-map {s- [:map {:closed true}
                     :sb/id
                     :post/id
                     :post/text-content
                     :ts/created-by
                     :ts/created-at
                     (? :post/comments)
                     (? :post/do-not-follow)
                     (? :post/followers)]}
   :comment/as-map {s- [:map {:closed true}
                        :sb/id
                        :ts/created-at
                        :ts/created-by
                        :comment/id
                        :comment/text]}})

(def sb-projects
  {:project/id unique-string-id
   :project/board (ref :one #{:board/id})
   :project/fields (merge (ref :many #{:field/id})
                          s/component)
   :project/open-requests {:doc "Currently active requests for help"
                           s- [:sequential :request/map]},
   :project/summary-text {:doc "Short description of project suitable for card or <head> meta"
                          s- :string
                          :db/fulltext true}
   :project/title {s- :string, :db/fulltext true}
   :project/team-complete? {:doc "Project team marked sufficient"
                            s- :boolean}
   :project/video {:doc "Primary video for project (distinct from fields)"
                   s- :video/value}
   :project/admin-approved? {:doc "Set by an admin when :action/project.approve policy is present. Unapproved projects are hidden."
                             s- :boolean}
   :project/badges {:doc "A badge is displayed on a project with similar formatting to a tag. Badges are ad-hoc, not defined at a higher level.",
                    s- [:vector :content/badge]}
   :project/number {:doc "Number assigned to a project by its board (stored as text because may contain annotations)",
                    :todo "This could be stored in the board entity, a map of {project, number}, so that projects may participate in multiple boards"
                    s- :string}
   :project/admin-description {:doc "A description field only writable by an admin"
                               s- :text-content/block}
   :project/inactive? {:doc "Marks a project inactive, hidden."
                       s- :boolean}
   :project/sticky? {:doc "Show project with border at top of project list"
                     s- :boolean}
   :project/as-map {s- [:map {:closed true}
                        :sb/id
                        :project/id
                        :project/board
                        :project/title
                        :ts/created-at
                        :ts/updated-at
                        (? :membership/_entity)
                        (? [:project/card-classes {:doc "css classes for card"
                                                   :to-deprecate true}
                            [:sequential :string]])
                        (? :project/admin-approved?)
                        (? :project/badges)
                        (? :project/number)
                        (? :project/admin-description)
                        (? :project/inactive?)
                        (? :project/sticky?)
                        (? :project/fields)
                        (? :project/open-requests)
                        (? :project/summary-text)
                        (? :project/team-complete?)
                        (? :project/video)
                        (? :ts/created-by)
                        (? :ts/deleted-at)
                        (? :ts/modified-by)]}})

(def sb-requests
  {:request/text {:doc "Free text description of the request"
                  s- :string}
   :request/map {s- [:map {:closed true} :request/text]}})

(def sb-slack
  {:slack.app/bot-token {s- :string},
   :slack.app/bot-user-id {s- :string},
   :slack.app/id {s- :string}
   :slack.broadcast/id unique-string-id
   :slack.broadcast/response-channel-id (merge {:doc "Channel containing replies to a broadcast"
                                                s- :string}
                                               s/string),
   :slack.broadcast/response-thread-id (merge {:doc "Thread containing replies to a broadcast"
                                               s- :string}
                                              s/string),
   :slack.broadcast/slack.team (ref :one #{:slack.team/id})
   :slack.broadcast/text {s- :string}

   :slack.broadcast.reply/id unique-string-id
   :slack.broadcast.reply/text {s- :string}
   :slack.broadcast.reply/channel-id {:doc ""
                                      s- :string}
   :slack.broadcast.reply/slack.user (ref :one #{:slack.user/id})
   :slack.broadcast/slack.broadcast.replies (merge s/many
                                                   s/ref
                                                   {s- [:sequential [:multi {:dispatch 'map?}
                                                                     [true :slack.broadcast.reply/as-map]
                                                                     [false (lookup-ref #{:slack.broadcast.reply/id})]]]})
   :slack.broadcast/slack.user (ref :one #{:slack.user/id})
   :slack.channel/id unique-string-id
   :slack.channel/project (ref :one #{:project/id}),
   :slack.channel/slack.team (ref :one #{:slack.team/id}),
   :slack.team/board (merge (ref :one #{:board/id})
                            {:doc "The sparkboard connected to this slack team"}),
   :slack.team/custom-messages {s- [:map {:closed true} :slack.team/custom-welcome-message],
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
   :slack.team/custom-welcome-message {s- :string,
                                       :doc "A message sent to each user that joins the connected workspace (slack team). It should prompt the user to connect their account."},
   :slack.user/id unique-string-id
   :slack.user/slack.team (ref :one #{:slack.team/id}),
   :slack.user/firebase-account-id {s- :string}

   :board/slack.team (merge s/component
                            (ref :one #{:slack.team/id}))
   :slack.broadcast/as-map {s- [:map {:closed true}
                                (? :slack.broadcast/response-channel-id)
                                (? :slack.broadcast/response-thread-id)
                                (? :slack.broadcast/slack.broadcast.replies)
                                (? :slack.broadcast/slack.team)
                                (? :slack.broadcast/slack.user)
                                :slack.broadcast/id
                                :slack.broadcast/text]},
   :slack.broadcast.reply/as-map {s- [:map {:closed true}
                                      :slack.broadcast.reply/id
                                      :slack.broadcast.reply/text
                                      :slack.broadcast.reply/slack.user
                                      :slack.broadcast.reply/channel-id]}
   :slack.channel/as-map {s- [:map {:closed true}
                              :slack.channel/id
                              :slack.channel/project
                              :slack.channel/slack.team]},
   :slack.team/as-map {s- [:map {:closed true}
                           (? :slack.team/custom-messages)
                           (? :slack.team/invite-link)
                           :slack.team/board
                           :slack.team/id
                           :slack.team/name
                           :slack.team/slack.app]},
   :slack.user/as-map {s- [:map {:closed true}
                           :slack.user/id
                           :slack.user/firebase-account-id
                           :slack.user/slack.team]},})

(def sb-social-feed
  {:social-feed.twitter/hashtags (merge s/many
                                        {s- [:set :string]})
   :social-feed.twitter/mentions (merge s/many
                                        {s- [:set :string]})
   :social-feed.twitter/profiles (merge s/many
                                        {s- [:set :string]})
   :social/feed {:doc "Settings for a live feed of social media related to an entity"
                 s- [:map {:closed true}
                     (? :social-feed.twitter/hashtags)
                     (? :social-feed.twitter/profiles)
                     (? :social-feed.twitter/mentions)]}})

(def sb-social-sharing
  {:social/sharing-button {s- [:enum
                               :social.sharing-button/facebook
                               :social.sharing-button/twitter
                               :social.sharing-button/qr-code]}})

(def sb-tags
  {:tag/ad-hoc {s- [:map :tag.ad-hoc/label]}
   :tag/id unique-string-id
   :tag/background-color {s- :html/color},
   :tag/label {s- :string},
   :tag/managed-by (merge {:doc "The entity which manages this tag"}
                          (ref :one #{:board/id})),
   :tag/restricted? {:doc "Tag may only be modified by an admin of the owner of this tag"
                     s- :boolean}
   :tag.ad-hoc/label {s- :string}
   :tag/as-map {:doc "Description of a tag which may be applied to an entity."
                s- [:map {:closed true}
                    :sb/id
                    :tag/label
                    :tag/id
                    :tag/managed-by
                    (? :tag/background-color)
                    (? :tag/restricted?)]}})

(def sb-text-content
  {:text-content/format {s- [:enum
                             :text.format/html
                             :text.format/markdown]}
   :text-content/string {s- :string}
   :text-content/block {s- [:map {:closed true}
                            :text-content/format
                            :text-content/string]}})

(def sb-thread
  {:thread/id unique-string-id
   :thread/members (merge (ref :many #{:member/id})
                          {:doc "Set of participants in a thread."}),
   :thread.message/id unique-string-id
   :thread.message/text {s- :string}
   :thread/messages {:doc "List of messages in a thread.",
                     :order-by :ts/created-at
                     s- [:sequential :thread.message/as-map]},
   :thread/read-by (merge
                    (ref :many #{:member/id})
                    {:doc "Set of members who have read the most recent message.",
                     :todo "Map of {member, last-read-message} so that we can show unread messages for each member."})
   :thread/as-map {s- [:map {:closed true}
                       :sb/id
                       :ts/created-at
                       :ts/updated-at
                       :thread/id
                       :thread/members
                       :thread/messages
                       :thread/read-by]},
   :thread.message/as-map {s- [:map {:closed true}
                               :thread.message/id
                               :thread.message/text
                               :ts/created-by
                               :ts/created-at]}})

(def sb-assets
  {:asset/provider [:enum :s3]
   :s3/object-key {s- :string}
   :s3/bucket {s- :string}
   :s3/upload-complete? {s- :boolean}
   :s3/as-map {s- [:map {:closed true}
                   :asset/provider
                   :sb/id
                   :s3/object-key
                   :s3/bucket
                   :ts/created-at
                   :ts/created-by
                   (? :ts/deleted-at)]}})

(def sb-ts
  {:ts/created-at {s- 'inst?, :doc "Date the entity was created"},
   :ts/created-by (merge (ref :one #{:member/id :account/id})
                         {:doc "Member or account who created this entity"}),
   :ts/deleted-at {:doc "Date when entity was marked deleted"
                   :todo "Excise deleted data after a grace period"
                   s- 'inst?}
   :ts/modified-by (merge (ref :one #{:member/id})
                          {:doc "Member who last modified this entity"}),
   :ts/updated-at {s- 'inst?
                   :doc "Date the entity was last modified"}})

(def sb-webhooks
  {:webhook/event {s- [:enum
                       :event.board/update-member
                       :event.board/new-member]}
   :webhook/subscriptions {s- [:map-of :webhook/event
                               [:map {:closed true} :webhook/url]]}
   :webhook/url {s- :http/url}})

(def sb-schema
  (-> (merge sb-badges
             sb-board
             sb-collections
             sb-discussion
             sb-domains
             sb-fields
             sb-firebase-account
             sb-memberships
             sb-util
             sb-i18n
             sb-images
             sb-member
             sb-member-vote
             sb-notification
             sb-org
             sb-posts
             sb-projects
             sb-requests
             sb-slack
             sb-social-feed
             sb-social-sharing
             sb-tags
             sb-text-content
             sb-thread
             sb-ts
             sb-assets
             sb-webhooks
             {:sb/id (merge s/unique-id
                            s/uuid
                            {s- :uuid})})
      (update-vals infer-db-type)
      (doto (as-> schema
                  (swap! !registry merge (update-vals schema s-))))))

(comment
 (->> sb-schema (remove (comp :malli/schema val))))

(def id-keys
  (into #{}
        (comp (filter #(= :db.unique/identity (:db/unique (val %))))
              (map key))
        sb-schema))

(def nested-ref-keys
  (into #{}
        (comp (filter (comp :db/nested-ref? val))
              (map key))
        sb-schema))

(def entity-schemas (into {} (map (fn [k] [k (keyword (namespace k) "entity")])) id-keys))

(defn unique-keys [m]
  (cond (map? m) (concat (some-> (select-keys m id-keys) (u/guard seq) list)
                         (->> (select-keys m nested-ref-keys)
                              vals
                              (mapcat unique-keys)))
        (sequential? m) (mapcat unique-keys m)))

(defn entity-schema [m]
  (some entity-schemas (keys m)))

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; For validation of incomplete incoming data, e.g. to create an entity

(defn str-uuid? [x]
  (and (string? x)
       (try (java.util.UUID/fromString x)
            (catch java.lang.IllegalArgumentException _iae
              nil))))

(def proto ;; FIXME this name --DAL 2023-02-22
  "Schema for validation"
  {:org [:map {:closed true}
         [:org/id [:string {:min 2}]]
         [:org/title [:string {:min 2}]]
         [:ts/created-by any?]]

   :board [:map {:closed true}
           [:board/id [:fn str-uuid?]]
           [:board/org [:tuple keyword? string?]]
           [:board/title [:string {:min 2}]]
           [:ts/created-by any?]]

   :project [:map {:closed true}
             [:project/id]
             [:project/board [:tuple keyword? string?]]
             [:project/title [:string {:min 2}]]
             [:ts/created-by any?]]

   :member [:map {:closed true}
            [:member/id]
            [:member/board [:tuple keyword? string?]]
            [:member/name [:string {:min 2}]]
            [:member/password [:string {:min 50}]] ;; FIXME this might belong on a separate user entity?
            [:ts/created-by any?]]})


(comment
 (m/validate (:org proto)
             {:org/id (str (random-uuid))
              :org/title "foo"
              :ts/created-by {:account/id "DEV:FAKE"}})

 (m/validate (:org proto)
             {:org/id (str (random-uuid))
              :org/title "foo"
              :ts/created-by {:account/id "DEV:FAKE"}
              :foo "bar"})

 (m/validate (:board proto)
             {:board/id (str (random-uuid))
              :board/org [:org/id "opengeneva"]
              :board/title "opengeneva board foo 123"
              :ts/created-by {:account/id "DEV:FAKE"}})

 (m/validate (:project proto)
             {:project/id (str (random-uuid))
              :project/board [:board/id "-MtC_Yd7VGM3fs2J2ibl"]
              :project/title "open innovation project AAAAAAAAAAAA"
              :ts/created-by {:account/id "DEV:FAKE"}})

 )
