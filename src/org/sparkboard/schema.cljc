(ns org.sparkboard.schema
  (:refer-clojure :exclude [ref])
  (:require [malli.core :as m]
            [malli.generator :as mg]
            [malli.registry :as mr]
            [re-db.schema :as s]
            [tools.sparkboard.util :as u]))

(def !registry (atom (m/default-schemas)))
(mr/set-default-registry! (mr/mutable-registry !registry))

(defn gen [s] (take 3 (repeatedly #(mg/generate s))))

(def s- :malli/schema)

(defn lookup-ref [ks]
  [:tuple (into [:enum] ks) :string])

(defn ref
  "returns a schema entry for a ref (one or many)"
  ([cardinality] (case cardinality :one (merge s/ref
                                               s/one
                                               {s- [:tuple :qualified-keyword :string]})
                                   :many (merge s/ref
                                                s/many
                                                {s- [:sequential [:tuple :qualified-keyword :string]]})))
  ([cardinality ks]
   (let [nested? (= 1 (count ks))
         leaf-type (if nested?
                     [:multi {:dispatch 'map?}
                      [true [:ref (keyword "sb" (namespace (first ks)))]]
                      [false (lookup-ref ks)]]
                     (lookup-ref ks))]
     (case cardinality :one (merge s/ref
                                   s/one
                                   {s- leaf-type}
                                   (when nested? {:db/nested-ref? true}))
                       :many (merge s/ref
                                    s/many
                                    {s- [:sequential leaf-type]}
                                    (when nested? {:db/nested-ref? true}))))))

(def unique-string-id (merge s/unique-id
                             s/string
                             {s- :string}))

(defn ? [k]
  (if (keyword? k)
    [k {:optional true}]
    (do (assert (vector? k))
        (if (map? (second k))
          (update k 1 assoc :optional true)
          (into [(first k) {:optional true}] (rest k))))))

(defn infer-db-type [m]
  (let [inferred-type (when (and (s- m) (not (:db/type m)))
                        (let [base-mappings {:string s/string
                                             :boolean s/boolean
                                             :keyword s/keyword
                                             :http/url s/string
                                             :html/color s/string
                                             :int s/long #_s/bigint
                                             'inst? s/instant}
                              known-bases (set (keys base-mappings))
                              malli-type (s- m)
                              malli-base (or (known-bases malli-type)
                                             (when (vector? malli-type)
                                               (or (when (and (= :db.cardinality/many (:db/cardinality m))
                                                              (#{:sequential :vector :set} (first malli-type)))
                                                     (known-bases (second malli-type)))
                                                   (when (#{:enum} (first malli-type))
                                                     (let [x (second malli-type)]
                                                       (cond (keyword? x) :keyword
                                                             (string? x) :string)))
                                                   (when (#{:re} (first malli-type))
                                                     :string))))]
                          (base-mappings malli-base)))]
    (when (vector? m)
      (prn :m m))
    (merge inferred-type m)))

(def sb-authorization
  {:action/policy {:doc "A policy describes how an action is restricted"
                   s- [:map {:closed true} :policy/requires-role]}
   :policy/requires-role {:doc "Policy: the action may only be completed by a member who has one of the provided roles."
                          s- [:set :grant/role]},
   :policy/map {s- [:map-of [:qualified-keyword {:namespace :action}] :action/policy]}
   :board/policies {s- :policy/map}})
(def sb-badges
  {:content/badge {s- [:map {:closed true} :badge/label]}
   :badge/label {s- :string
                 :spark/user-text true}})
(def sb-board
  {:board/id unique-string-id
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
   :board.landing-page/description-content {:doc "Primary description of a board, displayed in header"
                                            s- :text-content/block
                                            :db/fulltext true}
   :board.landing-page/instruction-content {:doc "Secondary instructions for a board, displayed above projects"
                                            s- :text-content/block
                                            :db/fulltext true},
   :board.landing-page/learn-more-url {:doc ""
                                       s- :http/url}
   :member.settings/max-projects {:doc "Set a maximum number of projects a member may join"
                                  s- :int}
   :member.settings/private-threads? {:doc "Enable the private messaging feature for this board"
                                      s- :boolean}
   :board/sticky-border-color {:doc "Border color for sticky projects", s- :html/color}
   :sb/board {s- [:map {:closed true}
                  (? :board/is-template?)
                  (? :board/labels)
                  (? :board/policies)
                  (? :board.registration/codes)
                  (? :board/slack.team)
                  (? :webhook/subscriptions)
                  (? :board.member-vote/open?)
                  (? :board.landing-page/description-content)
                  (? :board.landing-page/instruction-content)
                  (? :board.landing-page/learn-more-url)
                  (? :board.member/fields)
                  (? :member.settings/max-projects)
                  (? :member.settings/tags)
                  (? :project.settings/field-specs)
                  (? :project.settings/max-members)
                  (? :project.settings/sharing-buttons)
                  (? :project.settings/show-numbers?)
                  (? :board/sticky-border-color)
                  (? :board.registration.invitation-email/body-text)
                  (? :board.registration/pre-registration-content)
                  (? :board.registration/newsletter-subscription-field?)
                  (? :board.registration/register-at-url)
                  (? :html/css)
                  (? :html/js)
                  (? :html/meta-description)
                  (? :entity/domain)
                  (? :i18n/default-locale)
                  (? :i18n/extra-translations)
                  (? :i18n/suggested-locales)
                  (? :map/image-urls)
                  (? :social/feed)
                  (? :ts/deleted-at)
                  :board/id
                  :board/org
                  :board/title
                  :member.settings/private-threads?
                  :board.registration/open?
                  :ts/created-at
                  :visibility/public?]}})
(def sb-collections
  {:collection/id unique-string-id
   :collection/boards (ref :many #{:board/id})
   :collection/title {s- :string}
   :sb/collection {s- [:map {:closed true}
                       (? :map/image-urls)
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
   :sb/discussion {s- [:map {:closed true}
                       (? :discussion/followers)
                       (? :discussion/posts)
                       :discussion/id
                       :discussion/project
                       :ts/created-at]}})
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
   :sb/domain (merge (ref :one #{:domain/name})
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
  {:board.member/fields {s- [:sequential :sb/field.spec]}
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
   :field.spec/id unique-string-id
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
                       :field.image/url
                       [:field/type [:= :field.type/image]]]]
                     [:field.type/link-list
                      [:map {:closed true}
                       [:field.link-list/items
                        [:sequential :field.link-list/item]]
                       [:field/type [:= :field.type/link-list]]]]
                     [:field.type/select
                      [:map {:closed true}
                       :field.select/value
                       [:field/type [:= :field.type/select]]]]
                     [:field.type/text-content
                      [:map {:closed true}
                       :text-content/block
                       [:db/fulltext [:= true]]
                       [:field/type [:= :field.type/text-content]]]]
                     [:field.type/video
                      [:map {:closed true}
                       :field.video/value
                       [:field/type [:= :field.type/video]]]]]}
   :field.video/value {s- [:multi {:dispatch 'first}
                           [:field.video/vimeo-url [:tuple [:= :field.video/vimeo-url] :http/url]]
                           [:field.video/youtube-url [:tuple [:= :field.video/youtube-url] :http/url]]
                           [:field.video/youtube-id [:tuple [:= :field.video/youtube-id] :string]]]}
   :field.spec.option/color {s- :html/color},
   :field.spec.option/default? {s- :boolean},
   :field.spec.option/label {s- :string},
   :field.spec.option/value {s- :string},
   :field.video/format {s- [:enum :video.format/vimeo-url :video.format/youtube-url :video.format/youtube-id]},
   :field.video/url {s- :http/url},
   :field.video/youtube-id {s- :string}
   :sb/field {s- [:map {:closed true}
                  :field/field.spec
                  :field/id
                  :field/value]}
   :sb/field.spec {:doc "Description of a field."
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
                       :field/type]}})
(def sb-firebase-account
  {:firebase-account/id unique-string-id
   :firebase-account/email {s- :string}
   :sb/firebase-account {s- [:map {:closed true}
                             :firebase-account/id
                             :firebase-account/email]}})
(def sb-grants
  {:grant/_entity {s- [:sequential :sb/grant]}
   :grant/_member {s- [:sequential :sb/grant]}
   :grant/id (merge {:doc "ID field allowing for direct lookup of permissions"
                     :todo "Replace with composite unique-identity attribute :grant/member+entity"}
                    unique-string-id)
   :grant/entity (merge {:doc "Entity to which a grant applies"}
                        (ref :one #{:board/id
                                    :collection/id
                                    :org/id
                                    :project/id}))
   :grant/firebase-account (ref :one #{:firebase-account/id})
   :grant/member (merge {:doc "Member who is granted the roles"}
                        (ref :one #{:member/id})),
   :grant/role {:doc "A keyword representing a role which may be granted to a member",
                s- [:enum :role/admin :role/collaborator :role/member]},
   :grant/roles (merge {:doc "Set of roles granted",
                        s- [:set :grant/role]}
                       s/keyword
                       s/many)
   :sb/grant {s- [:and [:map {:closed true}
                        (? :grant/member)
                        (? :grant/firebase-account)
                        :grant/id
                        :grant/roles
                        :grant/entity]
                  [:fn {:error/message "Grant must contain :grant/member or :grant/firebase-account"}
                   '(fn [{:grant/keys [member firebase-account]}]
                      (and (or member firebase-account)
                           (not (and member firebase-account))))]]}})
(def sb-html
  {:html/card-classes {:doc "Classes for displaying this entity in card mode"
                       :todo "Deprecate in favour of controlled customizations"
                       s- [:vector :string]}
   :html/color {s- :string}
   :html/css {:doc "CSS styles (a string) relevant to a given entity"
              :todo "Deprecate in favour of defined means of customization"
              s- :string},
   :html/js {s- :string, :todo "Deprecate or otherwise restrict in hosted mode"},
   :html/meta-description {s- :string}})
(def sb-http
  {:http/url {s- [:re #"https?://.+\..+"]},})
(def sb-i18n
  {:i18n/default-locale {s- :string},
   :i18n/dictionary {s- [:map-of :string :string]}
   :i18n/extra-translations {:doc "Extra/override translations, eg. {'fr' {'hello' 'bonjour'}}",
                             s- [:map-of :i18n/name :i18n/dictionary]}
   :i18n/name {:doc "2-letter locale name, eg. 'en', 'fr'"
               s- [:re #"[a-z]{2}"]}
   :i18n/suggested-locales {:doc "Suggested locales (set by admin, based on expected users)",
                            s- [:vector :i18n/name]}})
(def sb-images
  {:map/image-urls {s- [:map-of
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
   :member.admin/inactive? {:doc "Marks a member inactive, hidden.",
                            :todo "If an inactive member signs in to a board, mark as active again."
                            s- :boolean},
   :member/board (ref :one #{:board/id})
   :member/email-frequency {s- [:enum
                                :member.email-frequency/never
                                :member.email-frequency/daily
                                :member.email-frequency/periodic
                                :member.email-frequency/instant]}
   :member/firebase-account (ref :one #{:firebase-account/id})
   :member/id unique-string-id
   :member/image-url {s- :http/url},
   :sb/member {s- [:map {:closed true}
                   (? :grant/_member)
                   (? :member/fields)
                   (? :member/tags.custom)
                   (? :member/image-url)
                   (? :member/newsletter-subscription?)
                   (? :member/tags)
                   (? :ts/deleted-at)
                   (? :ts/modified-by)
                   :member.admin/inactive?
                   :member/board
                   :member/email-frequency
                   :member/firebase-account
                   :member/id
                   :member/name
                   :member/new?
                   :member/project-participant?
                   :ts/created-at
                   :ts/updated-at]}})
(def sb-member-vote
  {:member-vote.entry/board (ref :one #{:board/id})
   :member-vote.entry/id (merge {:doc "ID composed of board-id:member-id"
                                 :todo "Replace with tuple attribute of board+member?"}
                                unique-string-id)
   :member-vote.entry/member (ref :one #{:member/id})
   :member-vote.entry/project (ref :one #{:project/id})

   :board.member-vote/open? {:doc "Opens a community vote (shown as a tab on the board)"
                             s- :boolean}
   :sb/member-vote.entry {s- [:map {:closed true}
                              :member-vote.entry/id
                              :member-vote.entry/board
                              :member-vote.entry/member
                              :member-vote.entry/project]}
   :sb/member-vote {s- [:map {:closed true}
                        :member-vote/board
                        :member-vote/open?]}})
(def sb-notification
  {:notification/comment (ref :one #{:post.comment/id}),
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
   :sb/notification {s- [:map {:closed true}
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
                         :ts/created-at]}})
(def sb-org
  {:board.settings/show-org-tab? {:doc "When true, boards should visually link to the parent organization (in main nav)"
                                  s- :boolean}
   :org/id unique-string-id
   :org/title {s- :string}
   :board.settings/default-template (merge {:doc "Default template (a board with :board/is-template? true) for new boards created within this org"}
                                           (ref :one #{:board/id}))
   :sb/org {s- [:map {:closed true}
                (? :board.settings/show-org-tab?)
                (? :map/image-urls)
                (? :board.settings/default-template)
                (? :social/feed)
                (? :i18n/default-locale)
                (? :i18n/suggested-locales)
                :org/title
                :org/id
                :entity/domain
                :visibility/public?
                :ts/created-by]}})
(def sb-posts
  {:post/_discussion {s- [:sequential :sb/post]}
   :post/id unique-string-id
   :post/comments (merge (ref :many #{:post.comment/id})
                         s/component)
   :post/text-content {s- :text-content/block
                       :db/fulltext true}
   :post/do-not-follow (merge
                        {:doc "Members who should not auto-follow this post after replying to it"}
                        (ref :many #{:member/id}))
   :post/followers (merge {:doc "Members who should be notified upon new replies to this post"}
                          (ref :many #{:member/id}))
   :post.comment/id unique-string-id
   :post.comment/text {s- :string}
   :sb/post {s- [:map {:closed true}
                 (? :post/comments)
                 (? :post/do-not-follow)
                 (? :post/followers)
                 :post/id
                 :post/text-content
                 :ts/created-by
                 :ts/created-at]}
   :sb/post.comment {s- [:map {:closed true}
                         :ts/created-at
                         :ts/created-by
                         :post.comment/id
                         :post.comment/text]}})
(def sb-projects
  {:project.settings/field-specs (merge (ref :many #{:field.spec/id})
                                        {s- [:sequential :sb/field.spec]})
   :project.settings/max-members {:doc "Set a maximum number of members a project may have"
                                  s- :int}
   :project.settings/sharing-buttons {:doc "Which social sharing buttons to display on project detail pages",
                                      s- [:map-of :social/sharing-button :boolean]}
   :project.settings/show-numbers? {:doc "Show 'project numbers' for this board"
                                    s- :boolean}
   :project/id unique-string-id
   :project/board (ref :one #{:board/id})
   :project/fields (merge (ref :many #{:field/id})
                          s/component)
   :project/open-requests {:doc "Currently active requests for help"
                           s- [:sequential :request/map]},
   :project/summary-text {:doc "Short description of project suitable for card or <head> meta"
                          s- :string
                          :db/fulltext true}
   :project/title {s- :string, :db/fulltext true}
   :project/viable-team? {:doc "Project has sufficient members to proceed"
                          s- :boolean}
   :project/video {:doc "Primary video for project (distinct from fields)"
                   s- :field.video/value}
   :project.admin/approved? {:doc "Set by an admin when :action/project.approve policy is present. Unapproved projects are hidden."
                             s- :boolean}
   :project.admin/badges {:doc "A badge is displayed on a project with similar formatting to a tag. Badges are ad-hoc, not defined at a higher level.",
                          s- [:vector :content/badge]}
   :project.admin/board.number {:doc "Number assigned to a project by its board (stored as text because may contain annotations)",
                                :todo "This could be stored in the board entity, a map of {project, number}, so that projects may participate in multiple boards"
                                s- :string}
   :project.admin/description-content {:doc "A description field only writable by an admin"
                                       s- :text-content/block}
   :project.admin/inactive? {:doc "Marks a project inactive, hidden."
                             s- :boolean}
   :project.admin/sticky? {:doc "Show project with border at top of project list"
                           s- :boolean}
   :sb/project {s- [:map {:closed true}
                    (? :grant/_entity)
                    (? :html/card-classes)
                    (? :project.admin/approved?)
                    (? :project.admin/badges)
                    (? :project.admin/board.number)
                    (? :project.admin/description-content)
                    (? :project.admin/inactive?)
                    (? :project.admin/sticky?)
                    (? :project/fields)
                    (? :project/open-requests)
                    (? :project/summary-text)
                    (? :project/viable-team?)
                    (? :project/video)
                    (? :ts/created-by)
                    (? :ts/deleted-at)
                    (? :ts/modified-by)
                    :project/board
                    :project/id
                    :project/title
                    :ts/created-at
                    :ts/updated-at]}})
(def sb-registration
  {:board.registration.invitation-email/body-text {:doc "Body of email sent when inviting a user to a board."
                                                   s- :string},
   :board.registration/newsletter-subscription-field? {:doc "During registration, request permission to send the user an email newsletter"
                                                       s- :boolean},
   :board.registration/open? {:doc "Allows new registrations via the registration page. Does not affect invitations.",
                              s- :boolean},
   :board.registration/pre-registration-content {:doc "Content displayed on registration screen (before user chooses provider / enters email)"
                                                 s- :text-content/block},
   :board.registration/register-at-url {:doc "URL to redirect user for registration (replaces the Sparkboard registration page, admins are expected to invite users)",
                                        s- :http/url},
   :board.registration/codes {s- [:map-of :string [:map {:closed true} [:registration-code/active? :boolean]]]}})
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
                                                                     [true :sb/slack.broadcast.reply]
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
   :slack.user/slack.team (ref :one #{:slack.team/id}),
   :slack.user/firebase-account-id {s- :string}

   :board/slack.team (merge s/component
                            (ref :one #{:slack.team/id}))
   :sb/slack.broadcast {s- [:map {:closed true}
                            (? :slack.broadcast/response-channel-id)
                            (? :slack.broadcast/response-thread-id)
                            (? :slack.broadcast/slack.broadcast.replies)
                            (? :slack.broadcast/slack.team)
                            (? :slack.broadcast/slack.user)
                            :slack.broadcast/id
                            :slack.broadcast/text]},
   :sb/slack.broadcast.reply {s- [:map {:closed true}
                                  :slack.broadcast.reply/id
                                  :slack.broadcast.reply/text
                                  :slack.broadcast.reply/slack.user
                                  :slack.broadcast.reply/channel-id]}
   :sb/slack.channel {s- [:map {:closed true}
                          :slack.channel/id
                          :slack.channel/project
                          :slack.channel/slack.team]},
   :sb/slack.team {s- [:map {:closed true}
                       (? :slack.team/custom-messages)
                       (? :slack.team/invite-link)
                       :slack.team/board
                       :slack.team/id
                       :slack.team/name
                       :slack.team/slack.app]},
   :sb/slack.user {s- [:map {:closed true}
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
  {:member.settings/tags (ref :many #{:tag/id})
   :tag/ad-hoc {s- [:map :tag.ad-hoc/label]}
   :tag/id unique-string-id
   :tag/background-color {s- :html/color},
   :tag/label {s- :string},
   :tag/managed-by (merge {:doc "The entity which manages this tag"}
                          (ref :one #{:board/id})),
   :tag/restricted? {:doc "Tag may only be modified by an admin of the owner of this tag"
                     s- :boolean}
   :tag.ad-hoc/label {s- :string}
   :sb/tag {:doc "Description of a tag which may be applied to an entity."
            s- [:map {:closed true}
                (? :tag/background-color)
                (? :tag/restricted?)
                :tag/label
                :tag/id
                :tag/managed-by]}})
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
                     s- [:sequential :sb/thread.message]},
   :thread/read-by (merge
                    (ref :many #{:member/id})
                    {:doc "Set of members who have read the most recent message.",
                     :todo "Map of {member, last-read-message} so that we can show unread messages for each member."})
   :sb/thread {s- [:map {:closed true}
                   :ts/created-at
                   :ts/updated-at
                   :thread/id
                   :thread/members
                   :thread/messages
                   :thread/read-by]},
   :sb/thread.message {s- [:map {:closed true}
                           :thread.message/id
                           :thread.message/text
                           :ts/created-by
                           :ts/created-at]}})
(def sb-ts
  {:ts/created-at {s- 'inst?, :doc "Auto-generated creation-date of entity"},
   :ts/created-by (merge (ref :one #{:member/id :firebase-account/id}) {:doc "Member who created this entity"}),
   :ts/deleted-at {:doc "Deletion flag"
                   :todo "Excise deleted data after a grace period"
                   s- 'inst?}
   :ts/modified-by (ref :one #{:member/id})
   :ts/updated-at {s- 'inst?}})
(def sb-visibility
  {:visibility/public? {:doc "Contents of this entity can be accessed without authentication (eg. and indexed by search engines)"
                        s- :boolean}})
(def sb-webhooks
  {:webhook/event {s- [:enum
                       :event.board/update-member
                       :event.board/new-member]}
   :webhook/subscriptions {s- [:map-of :webhook/event
                               [:map {:closed true} :webhook/url]]}
   :webhook/url {s- :http/url}})

(def sb-schema
  (-> (merge sb-authorization
             sb-badges
             sb-board
             sb-collections
             sb-discussion
             sb-domains
             sb-fields
             sb-firebase-account
             sb-grants
             sb-html
             sb-http
             sb-i18n
             sb-images
             sb-member
             sb-member-vote
             sb-notification
             sb-org
             sb-posts
             sb-projects
             sb-registration
             sb-requests
             sb-slack
             sb-social-feed
             sb-social-sharing
             sb-tags
             sb-text-content
             sb-thread
             sb-ts
             sb-visibility
             sb-webhooks)
      (update-vals infer-db-type)
      (doto (as-> schema
                  (update-vals schema :malli/schema)
                  (swap! !registry merge schema)))))

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

(def entity-schemas (into {} (map (fn [k] [k (keyword "sb" (namespace k))])) id-keys))

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
