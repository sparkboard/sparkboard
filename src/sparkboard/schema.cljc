(ns sparkboard.schema
  (:refer-clojure :exclude [ref])
  (:require [malli.core :as m]
            [sparkboard.impl.schema :refer [s-
                                            string-lookup-ref
                                            unique-string-id
                                            unique-uuid
                                            ref
                                            ?
                                            infer-db-type]]
            [malli.error :refer [humanize]]
            [malli.registry :as mr]
            [re-db.schema :as s]
            [sparkboard.util :as u]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; For bulk data import

(def !registry (atom (m/default-schemas)))
(mr/set-default-registry! (mr/mutable-registry !registry))

(def sb-badges
  {:content/badge {s- [:map {:closed true} :badge/label]}
   :badge/label {s- :string
                 :spark/user-text true}})

(def sb-entity
  {:entity/id unique-uuid
   :entity/title {:doc "Title of entity, for card/header display."
                  s- :string
                  :db/fulltext true}
   :entity/kind {s- [:enum :board :org :collection :member :project]}
   :entity/description {:doc "Description of an entity (for card/header display)"
                        s- :prose/as-map
                        :db/fulltext true}
   :entity/field-entries (merge (ref :many :field-entry/as-map)
                                s/component)
   :entity/video {:doc "Primary video for project (distinct from fields)"
                  s- :video/value}
   :entity/public? {:doc "Contents of this entity can be accessed without authentication (eg. and indexed by search engines)"
                    s- :boolean}
   :entity/website {:doc "External website for entity"
                    s- :http/url}
   :entity/social-feed {s- :social/feed}
   :entity/images {s- [:map-of
                       [:qualified-keyword {:namespace :image}]
                       :http/url]}
   :entity/meta-description {:doc "Custom description for html page header"
                             s- :string}
   :entity/locale-default {s- :i18n/locale}
   :entity/locale-suggestions {:doc "Suggested locales (set by admin, based on expected users)",
                               s- :i18n/locale-suggestions}
   :entity/locale-dicts {s- :i18n/locale-dicts}
   :entity/created-at {s- 'inst?, :doc "Date the entity was created"},
   :entity/created-by (merge (ref :one)
                             {:doc "Member or account who created this entity"}),
   :entity/deleted-at {:doc "Date when entity was marked deleted"
                       :todo "Excise deleted data after a grace period"
                       s- 'inst?}
   :entity/modified-by (merge (ref :one)
                              {:doc "Member who last modified this entity"}),
   :entity/updated-at {s- 'inst?
                       :doc "Date the entity was last modified"}})

(def sb-board
  {:board/show-project-numbers? {s- :boolean
                                 :doc "Show 'project numbers' for this board"}
   :board/max-members-per-project {:doc "Set a maximum number of members a project may have"
                                   s- :int}
   :board/project-sharing-buttons {:doc "Which social sharing buttons to display on project detail pages",
                                   s- [:map-of :social/sharing-button :boolean]}
   :board/is-template? {:doc "Board is only used as a template for creating other boards",
                        s- :boolean},
   :board/labels {:unsure "How can this be handled w.r.t. locale?"
                  s- [:map-of [:enum
                               :label/member.one
                               :label/member.many
                               :label/project.one
                               :label/project.many] :string]},
   :board/org (ref :one)
   :board/instructions {:doc "Secondary instructions for a board, displayed above projects"
                        s- :prose/as-map},
   :board/max-projects-per-member {:doc "Set a maximum number of projects a member may join"
                                   s- :int}
   :board/sticky-color {:doc "Border color for sticky projects", s- :html/color}
   :board/member-tags (ref :many :tag/as-map)
   :board/project-fields (ref :many :field/as-map)
   :board/member-fields (ref :many :field/as-map)
   :board/registration-invitation-email-text {:doc "Body of email sent when inviting a user to a board."
                                              s- :string},
   :board/registration-newsletter-field? {:doc "During registration, request permission to send the user an email newsletter"
                                          s- :boolean},
   :board/registration-open? {:doc "Allows new registrations via the registration page. Does not affect invitations.",
                              s- :boolean},
   :board/registration-message {:doc "Content displayed on registration screen (before user chooses provider / enters email)"
                                s- :prose/as-map},
   :board/registration-url-override {:doc "URL to redirect user for registration (replaces the Sparkboard registration page, admins are expected to invite users)",
                                     s- :http/url},
   :board/registration-codes {s- [:map-of :string [:map {:closed true} [:registration-code/active? :boolean]]]}
   :board/rules {s- [:map-of
                     [:qualified-keyword {:namespace :action}]
                     [:map
                      [:policy/requires-role [:set :roles.role]]]]}
   :board/custom-css {:doc "Custom CSS for this board"
                      s- :string}
   :board/custom-js {:doc "Custom JS for this board"
                     s- :string}
   :board/as-map {s- [:map {:closed true}
                      :entity/id
                      :entity/title
                      :entity/created-at
                      :entity/public?
                      :entity/kind

                      :board/org
                      :board/registration-open?

                      (? :image/logo)
                      (? :image/logo-large)
                      (? :image/footer)
                      (? :image/background)
                      (? :image/sub-header)

                      (? :entity/website)
                      (? :entity/meta-description)
                      (? :entity/description)
                      (? :entity/domain)
                      (? :entity/locale-default)
                      (? :entity/locale-dicts)
                      (? :entity/locale-suggestions)
                      (? :entity/social-feed)
                      (? :entity/deleted-at)

                      (? :board/custom-css)
                      (? :board/custom-js)
                      (? :board/instructions)
                      (? :board/is-template?)
                      (? :board/labels)
                      (? :board/max-members-per-project)
                      (? :board/max-projects-per-member)
                      (? :board/member-fields)
                      (? :board/member-tags)
                      (? :board/rules)
                      (? :board/project-fields)
                      (? :board/project-sharing-buttons)
                      (? :board/registration-codes)
                      (? :board/registration-invitation-email-text)
                      (? :board/registration-message)
                      (? :board/registration-newsletter-field?)
                      (? :board/registration-url-override)
                      (? :board/show-project-numbers?)
                      (? :board/slack.team)
                      (? :board/sticky-color)

                      (? :member-vote/open?)
                      (? :webhook/subscriptions)]}})

(def sb-collections
  {:collection/boards (ref :many)
   :collection/as-map {s- [:map {:closed true}
                           :entity/id
                           :entity/kind
                           :collection/boards
                           :entity/title
                           :entity/domain
                           (? :image/logo)
                           (? :image/background)]}})

(def sb-discussion
  {:discussion/followers (ref :many),
   :discussion/posts (merge (ref :many :post/as-map)
                            s/component)
   :discussion/project (ref :one)
   :discussion/as-map {s- [:map {:closed true}
                           :entity/id
                           :discussion/project
                           :entity/created-at
                           (? :discussion/followers)
                           (? :discussion/posts)]}})

(def sb-domains
  {:domain.kind/url {s- :http/url}
   :domain/kind {s- [:enum
                     :domain.kind/url
                     :domain.kind/entity]}
   :domain.kind/entity (merge (ref :one)
                              {:doc "The entity this domain points to",})
   :domain/name (merge {:doc "A complete domain name, eg a.b.com",}
                       unique-string-id)
   :entity/domain (merge (ref :one) {:doc "Domain name linked to this entity"})
   :domain/as-map (merge (ref :one)
                         {s- [:multi {:dispatch :domain/kind}
                              [:domain.kind/url
                               [:map {:closed true}
                                [:domain/kind [:= :domain.kind/url]]
                                :domain.kind/url
                                :domain/name]]
                              [:domain.kind/entity
                               [:map {:closed true}
                                [:domain/kind [:= :domain.kind/entity]]
                                :domain.kind/entity
                                :domain/name]]]})})

(def sb-fields
  {:image/url {s- :http/url}

   :field/hint {s- :string},
   :field/id unique-uuid
   :field/label {s- :string},
   :field/default-value {s- :string}
   :field/options {s- (? [:sequential :field/option])},
   :field/option {s- [:map {:closed true}
                      (? :field-option/color)
                      (? :field-option/value)
                      :field-option/label]}
   :field/order {s- :int},
   :field/required? {s- :boolean},
   :field/show-as-filter? {:doc "Use this field as a filtering option"
                           s- :boolean},
   :field/show-at-create? {:doc "Ask for this field when creating a new entity"
                           s- :boolean},
   :field/show-on-card? {:doc "Show this field on the entity when viewed as a card"
                         s- :boolean},
   :field/type {s- [:enum
                    :field.type/images
                    :field.type/video
                    :field.type/select
                    :field.type/link-list
                    :field.type/prose
                    :field.type/prose]}

   :field-entry/id unique-uuid
   :field-entry/field (ref :one)
   :field-entry/value {s-
                       [:multi {:dispatch 'first}
                        [:field.type/images
                         [:tuple 'any?
                          [:sequential [:map {:closed true} :image/url]]]]
                        [:field.type/link-list
                         [:tuple 'any?
                          [:map {:closed true}
                           [:link-list/items
                            [:sequential :link-list/link]]]]]
                        [:field.type/select
                         [:tuple 'any?
                          [:map {:closed true}
                           [:select/value :string]]]]
                        [:field.type/prose
                         [:tuple 'any?
                          :prose/as-map]]
                        [:field.type/video :video/value
                         [:tuple 'any? [:map {:closed true} :video/value :video/type]]]]}
   :field-entry/as-map {s- [:map {:closed true}
                            :field-entry/id
                            :field-entry/field
                            :field-entry/value]}
   :link-list/link {:todo "Tighten validation after cleaning up db"
                    s- [:map {:closed true}
                        (? [:text :string])
                        [:url :string]]}
   :field-option/color {s- :html/color},
   :field-option/default {s- :string},
   :field-option/label {s- :string},
   :field-option/value {s- :string},
   :video/type {s- [:enum
                    :video.type/youtube-id
                    :video.type/youtube-url
                    :video.type/vimeo-url]}
   :video/value {s- :string}
   :field/as-map {:doc "Description of a field."
                  :todo ["Field specs should be definable at a global, org or board level."
                         "Orgs/boards should be able to override/add field.spec options."
                         "Field specs should be globally merged so that fields representing the 'same' thing can be globally searched/filtered?"]
                  s- [:map {:closed true}
                      :field/id
                      :field/order
                      :field/type
                      (? :field/hint)
                      (? :field/label)
                      (? :field/options)
                      (? :field/required?)
                      (? :field/show-as-filter?)
                      (? :field/show-at-create?)
                      (? :field/show-on-card?)]}})

(def sb-account
  {:account/email unique-string-id
   :account/email-verified? {s- :boolean}
   :account/display-name {s- :string}
   :account.provider.google/sub unique-string-id
   :account/last-sign-in {s- 'inst?}
   :account/password-hash {s- :string}
   :account/password-salt {s- :string}
   :account/photo (ref :one :asset/as-map)
   :account/locale {s- :i18n/locale}
   :account/as-map {s- [:map {:closed true}
                        :entity/id
                        :account/email
                        :account/email-verified?
                        :entity/created-at
                        (? :account/locale)
                        (? :account/last-sign-in)
                        (? :account/display-name)
                        (? :account/password-hash)
                        (? :account/password-salt)
                        (? :account/photo)
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

(def sb-roles
  {:roles/_entity {s- [:sequential :roles/as-map]}
   :roles/_member {s- [:sequential :roles/as-map]}
   :roles/id (merge {:doc "ID field allowing for direct lookup of permissions"
                     :derived-from [:roles/entity
                                    :roles/recipient]
                     :todo "Replace with composite unique-identity attribute :grant/member+entity"}
                    unique-uuid)
   :roles/entity (merge {:doc "Entity to which a grant applies"}
                        (ref :one))
   :roles/recipient (merge {:doc "Member or account who is granted the roles"}
                           (ref :one)),
   :roles.role {:doc "A keyword representing a role which may be granted to a member",
                s- [:enum :role/admin :role/collaborator :role/member]},
   :roles/roles (merge {:doc "Set of roles granted",
                        s- [:set :roles.role]}
                       s/keyword
                       s/many)
   :roles/as-map {s- [:and [:map {:closed true}
                            :roles/id
                            :roles/roles
                            :roles/entity
                            :roles/recipient]
                      [:fn {:error/message "Membership must contain :roles/member or :roles/account"}
                       '(fn [{:keys [:roles/recipient :roles/account]}]
                          (and (or recipient account)
                               (not (and recipient account))))]]}})

(def sb-util
  ;; TODO, tighten url
  {:http/url {s- [:re #"(?:[a-z]+?://)?.+\..+"]}
   ;; TODO - fix these in the db, remove "file" below
   :http/image-url {s- [:re #"(?i)https?://.+\..+\.(?:jpg|png|jpeg|gif|webp)$"]}
   :html/color {s- :string}
   :email {s- [:re {:error/message "should be a valid email"} #"^[^@]+@[^@]+$"]}})
(def sb-i18n
  {:i18n/locale {:doc "ISO 639-2 language code (eg. 'en')"
                 s- [:re #"[a-z]{2}"]}
   :i18n/default-locale {s- :string},
   :i18n/dict {s- [:map-of :string :string]}
   :i18n/locale-suggestions {s- [:sequential :i18n/locale]}
   :i18n/locale-dicts {:doc "Extra/override translations, eg. {'fr' {'hello' 'bonjour'}}",
                       s- [:map-of :i18n/locale :i18n/dict]}})

(def sb-member
  {:member/new? {s- :boolean},
   :member/newsletter-subscription? {s- :boolean},
   :member/tags (ref :many :tag/as-map)
   :member/ad-hoc-tags {s- [:sequential [:map {:closed true} :tag/label]]}
   :member/inactive? {:doc "Marks a member inactive, hidden."
                      :admin true
                      :todo "If an inactive member signs in to a board, mark as active again?"
                      s- :boolean},
   :member/board (ref :one)
   :member/email-frequency {s- [:enum
                                :member.email-frequency/never
                                :member.email-frequency/daily
                                :member.email-frequency/periodic
                                :member.email-frequency/instant]}
   :member/account (ref :one)
   :member/as-map {s- [:map {:closed true}
                       :entity/id
                       :entity/kind
                       :member/inactive?
                       :member/board
                       :member/email-frequency
                       :member/account
                       :member/new?
                       :entity/created-at
                       :entity/updated-at
                       (? :roles/_member)
                       (? :entity/field-entries)
                       (? :member/ad-hoc-tags)
                       (? :member/newsletter-subscription?)
                       (? :member/tags)
                       (? :entity/deleted-at)
                       (? :entity/modified-by)]}})

(def sb-member-vote
  {:member-vote/open? {:doc "Opens a community vote (shown as a tab on the board)"
                       s- :boolean}
   :ballot/as-map {s- [:map {:closed true}
                       :ballot/id
                       :ballot/board
                       :ballot/member
                       :ballot/project]}
   :ballot/board (ref :one)
   :ballot/id (merge unique-uuid
                     {:derived-from [:ballot/board
                                     :ballot/member
                                     :ballot/project]})
   :ballot/member (ref :one)
   :ballot/project (ref :one)})

(def sb-notification
  {:notification/comment (ref :one),
   :notification/discussion (ref :one)
   :notification/emailed? {:doc "The notification has been included in an email",
                           :todo "deprecate: log {:notifications/emailed-at _} per member"
                           s- :boolean},
   :notification/member (ref :one)
   :notification/post (ref :one)
   :notification/post.comment (ref :one)
   :notification/project (ref :one)
   :notification/recipient (ref :one),
   :notification/subject (merge
                           (ref :one)
                           {:doc "The primary entity referred to in the notification (when viewed"}),
   :notification/thread (ref :one)
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
                             :entity/id
                             :notification/emailed?
                             :notification/recipient
                             :notification/type
                             :notification/viewed?
                             :entity/created-at
                             (? :notification/discussion)
                             (? :notification/member)
                             (? :notification/post)
                             (? :notification/post.comment)
                             (? :notification/project)
                             (? :notification/thread)
                             (? :notification/thread.message.text)]}})

(def sb-org
  {:org/show-org-tab? {:doc "Boards should visibly link to this parent organization"
                       s- :boolean}
   :entity/id unique-uuid
   :org/default-board-template (merge {:doc "Default template (a board with :board/is-template? true) for new boards created within this org"}
                                      (ref :one))
   :org/as-map {s- [:map {:closed true}
                    :entity/id
                    :entity/title
                    :entity/kind
                    :entity/created-by
                    (? :image/logo)
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

(def sb-posts
  {:post/_discussion {s- [:sequential :post/as-map]}
   :post/comments (merge (ref :many :comment/as-map)
                         s/component)
   :post/text {s- :prose/as-map
               :db/fulltext true}
   :post/do-not-follow (merge
                         {:doc "Members who should not auto-follow this post after replying to it"}
                         (ref :many))
   :post/followers (merge {:doc "Members who should be notified upon new replies to this post"}
                          (ref :many))
   :comment/text {s- :string}
   :post/as-map {s- [:map {:closed true}
                     :entity/id
                     :post/text
                     :entity/created-by
                     :entity/created-at
                     (? :post/comments)
                     (? :post/do-not-follow)
                     (? :post/followers)]}
   :comment/as-map {s- [:map {:closed true}
                        :entity/id
                        :entity/created-at
                        :entity/created-by
                        :comment/text]}})

(def sb-projects
  {:project/board (ref :one)
   :project/open-requests {:doc "Currently active requests for help"
                           s- [:sequential :request/map]},
   :project/team-complete? {:doc "Project team marked sufficient"
                            s- :boolean}
   :project/admin-approved? {:doc "Set by an admin when :action/project.approve policy is present. Unapproved projects are hidden."
                             s- :boolean}
   :project/badges {:doc "A badge is displayed on a project with similar formatting to a tag. Badges are ad-hoc, not defined at a higher level.",
                    s- [:vector :content/badge]}
   :project/number {:doc "Number assigned to a project by its board (stored as text because may contain annotations)",
                    :todo "This could be stored in the board entity, a map of {project, number}, so that projects may participate in multiple boards"
                    s- :string}
   :project/admin-description {:doc "A description field only writable by an admin"
                               s- :prose/as-map}
   :project/inactive? {:doc "Marks a project inactive, hidden."
                       s- :boolean}
   :project/sticky? {:doc "Show project with border at top of project list"
                     s- :boolean}
   :project/as-map {s- [:map {:closed true}
                        :entity/id
                        :entity/kind
                        :project/board
                        (? :entity/field-entries)
                        (? :entity/video)
                        (? :entity/created-by)
                        (? :entity/deleted-at)
                        (? :entity/modified-by)
                        :entity/title
                        :entity/created-at
                        :entity/updated-at
                        (? :roles/_entity)
                        (? [:project/card-classes {:doc "css classes for card"
                                                   :to-deprecate true}
                            [:sequential :string]])
                        (? :project/admin-approved?)
                        (? :project/badges)
                        (? :project/number)
                        (? :project/admin-description)
                        (? :project/inactive?)
                        (? :project/sticky?)
                        (? :project/open-requests)
                        (? :entity/description)
                        (? :project/team-complete?)]}})

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
   :slack.broadcast/slack.team (ref :one)
   :slack.broadcast/text {s- :string}

   :slack.broadcast.reply/id unique-string-id
   :slack.broadcast.reply/text {s- :string}
   :slack.broadcast.reply/channel-id {:doc ""
                                      s- :string}
   :slack.broadcast.reply/slack.user (ref :one)
   :slack.broadcast/slack.broadcast.replies (ref :many :slack.broadcast.reply/as-map)
   :slack.broadcast/slack.user (ref :one)
   :slack.channel/id unique-string-id
   :slack.channel/project (ref :one),
   :slack.channel/slack.team (ref :one),
   :slack.team/board (merge (ref :one)
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
   :slack.user/slack.team (ref :one),
   :slack.user/firebase-account-id {s- :string}

   :board/slack.team (merge (ref :one :slack.team/as-map)
                            s/component)
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
  {:tag/id unique-uuid
   :tag/background-color {s- :html/color},
   :tag/label {s- :string},,
   :tag/restricted? {:doc "Tag may only be modified by an admin of the owner of this tag"
                     s- :boolean}
   :tag/as-map {:doc "Description of a tag which may be applied to an entity."
                s- [:map {:closed true}
                    :tag/id
                    :tag/label
                    (? :tag/background-color)
                    (? :tag/restricted?)]}})

(def sb-prose
  {:prose/format {s- [:enum
                      :prose.format/html
                      :prose.format/markdown]}
   :prose/string {s- :string}
   :prose/as-map {s- [:map {:closed true}
                      :prose/format
                      :prose/string]}})

(def sb-thread
  {:thread/members (merge (ref :many)
                          {:doc "Set of participants in a thread."}),
   :thread.message/id unique-string-id
   :thread.message/text {s- :string}
   :thread/messages {:doc "List of messages in a thread.",
                     :order-by :entity/created-at
                     s- [:sequential :thread.message/as-map]},
   :thread/read-by (merge
                     (ref :many)
                     {:doc "Set of members who have read the most recent message.",
                      :todo "Map of {member, last-read-message} so that we can show unread messages for each member."})
   :thread/as-map {s- [:map {:closed true}
                       :entity/id
                       :entity/created-at
                       :entity/updated-at
                       :thread/members
                       :thread/messages
                       :thread/read-by]},
   :thread.message/as-map {s- [:map {:closed true}
                               :entity/id
                               :entity/created-by
                               :entity/created-at
                               :thread.message/text]}})


(def sb-assets
  {:asset/provider {s- [:enum
                        :asset.provider/s3
                        :asset.provider/external-link]}
   :asset/id unique-uuid
   :asset/content-type {s- :string}
   :asset/size {s- 'number?}
   :src {s- :string}

   :s3/bucket-name {s- :string}

   :asset/as-map {s- [:map {:closed true}
                      :asset/provider
                      :asset/id
                      [:src :src]
                      (? :s3/bucket-name)
                      (? :asset/content-type)
                      (? :asset/size)
                      (? :entity/created-by)
                      (? :entity/created-at)]}

   :image/logo (ref :one :asset/as-map)
   :image/logo-large (ref :one :asset/as-map)
   :image/footer (ref :one :asset/as-map)
   :image/background (ref :one :asset/as-map)
   :image/sub-header (ref :one :asset/as-map)})
(comment
  (m/explain :asset/as-map {:src ""
                            :asset/id (random-uuid)
                            :asset/provider :asset.provider/s3}))
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
             sb-entity
             sb-fields
             sb-account
             sb-roles
             sb-util
             sb-i18n
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
             sb-prose
             sb-thread
             sb-assets
             sb-webhooks)
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

(def ref-keys
  (into #{}
        (comp (filter (comp #{:db.type/ref} :db/valueType val))
              (map key))
        sb-schema))

(def entity-schemas (into {} (map (fn [k] [k (keyword (namespace k) "entity")])) id-keys))

(defn unique-keys [m]
  (cond (map? m) (concat (some-> (select-keys m id-keys) (u/guard seq) list)
                         (->> (select-keys m ref-keys)
                              vals
                              (mapcat unique-keys)))
        (sequential? m) (mapcat unique-keys m)))

(defn entity-schema [m]
  (some entity-schemas (keys m)))

;; previously used - relates to :notification/subject-viewed?
(def notification-subjects {:notification.type/new-project-member :entity/id
                            :notification.type/new-thread-message :thread/id
                            :notification.type/new-discussion-post :post/id
                            :notification.type/new-post-comment :post/id})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; For validation of incomplete incoming data, e.g. to create an entity

(defn str-uuid? [x]
  (and (string? x)
       #?(:clj  (try #?(:clj (java.util.UUID/fromString x))
                     (catch java.lang.IllegalArgumentException _iae
                       nil))
          :cljs true)))

(comment
  ())

(-> (ref :one)
    s-
    (malli.core/explain [:entity/id 'uuid?]))