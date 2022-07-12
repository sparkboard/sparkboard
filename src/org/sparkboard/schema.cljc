(ns org.sparkboard.schema
  (:refer-clojure :exclude [ref])
  (:require [re-db.schema :as s]
            [malli.core :as m]
            [malli.generator :as mg]
            [malli.experimental.lite :as l]
            [malli.registry :as mr]
            [malli.provider :as mp]))

(defonce !registry (atom (m/default-schemas)))
(mr/set-default-registry! (mr/mutable-registry !registry))

(defn register! [m] (swap! !registry merge m))

(defn gen [s] (take 3 (repeatedly #(mg/generate s))))

(gen [:map [:image-url/logo {:optional true}]])

(gen :db/lookup-ref)

;; TODO
;; malli for every field,
;; import into datalevin

(def ref (merge s/ref {:spark/schema :db/lookup-ref}))


(def str? ['string? {:optional true}])
(def bool? ['boolean? {:optional true}])
(def lookup-ref? :db/lookup-ref)

(def schema :spark/schema)
(defn S [schema] {:spark/schema schema})
(defn system [k] {:spark/system k})

(def url? [:re #"https?://.+\..+"])
(def image-urls? [:map-of 'string? url?])
(def locale? [:re #"[a-z]{2}$"])

(comment
 ;; gen
 (gen [:map-of locale? [:map-of 'string? 'string?]])
 (gen [pos-int?])
 (gen image-urls?)
 (gen url?)
 (gen field?)
 (gen (l))
 )

(defn ref
  ([cardinality] (case cardinality :one (merge s/ref
                                               s/one
                                               (S :db/lookup-ref))
                                   :many (merge s/ref
                                                s/many
                                                (S [:vector :db/lookup-ref]))))
  ([cardinality ks] (assoc (ref cardinality) :db/ref-keys ks)))

(def unique-string-id (merge s/unique-id
                             s/string
                             (S 'string?)))
(def bool (merge s/boolean
                 (S 'boolean?)))

(def http-url (S :http/url))
(def html-color (S :html/color))


(def date {:db/valueType :db.type/instant
           })

(gen [:enum :A :B :C])

(def extra-schema
  {:slack.channel/slack.team {}
   :slack.channel/project {}
   :slack.broadcast/text {}
   :slack.broadcast.reply/slack.user {}
   :slack.broadcast.reply/text {}
   :slack.user/slack.team {}
   :slack.app/bot-token {}
   :slack.app/bot-user-id {}

   :board/is-template? {:doc "Board is only used as a template for creating other boards"
                        :todo "Do not store this as a board entity, rather - edn?"}

   :db/lookup-ref {:spark/schema [:tuple :qualified-keyword 'string?]},

   :entity/tag {:doc "Description of a tag which may be applied to an entity."},

   :html/color {:spark/schema 'string?},
   :html/js {:spark/schema 'string?
             :todo "Deprecate or otherwise restrict in hosted mode"}
   :html/card-classes {:doc "Classes for displaying this entity in card mode"
                       :todo "Deprecate in favour of controlled customizations"}
   :grant/entity {:doc "Entity to which a grant applies"}
   :grant/roles {:doc "Set of roles granted"}
   :grant/member {:doc "Member who is granted the roles"}

   :http/url {:spark/schema [:re #"https?//.+\..+"]},

   :post/followers (merge (ref :many #{:member/id})
                          {:doc "Members who should be notified upon new replies to this post"})
   :post/do-not-follow (merge (ref :many #{:member/id})
                              {:doc "Members who should not auto-follow this post after replying to it"})
   :post/content {}
   :content/format {:spark/schema [:enum :html :markdown]}
   :content/html {}

   :field/id {:todo "Should be a tuple field of [:field/parent :field/spec] ?"}

   ;; static project fields managed by admins
   :project.admin/inactive? {:doc "Marks a project inactive, hidden."}
   :project.admin/approved? {:doc "Set by an admin when :board.project/requires-approval? is true. Unapproved projects are hidden."}
   :project.admin/badges {:doc "A badge is displayed on a project with similar formatting to a tag. Badges are ad-hoc, not defined at a higher level."}
   :project.admin/sticky? {:doc "Show project with border at top of project list"}
   :project.admin/board.number {:doc "Number assigned to a project by its board"
                                :todo "This could be stored in the board entity, a map of {project, number}"}
   :project.admin/description {:doc "A description field only writable by an admin"}

   ;; static project fields managed by its team
   :project/summary {:doc "Short description of project suitable for card or <head> meta"}
   :project/video-url {:doc "Primary video for project (distinct from fields)"}
   :project/viable-team? {:doc "Project has sufficient members to proceed"}
   :project/open-requests {:doc "Currently active requests for help"}


   :request/text {:doc "Free text description of the request"}

   :slack.broadcast/response-channel (ref :one #{:slack.channel/id})
   :slack.broadcast/response-thread (ref :one #{:slack.thread/id})
   :slack.team/board {:doc "The sparkboard connected to this slack team"}
   :slack.team/custom-messages {:spark/schema [:map :slack.team.custom-message/welcome]
                                :doc "Custom messages for a Slack integration"}
   :slack.team.custom-message/welcome {:spark/schema 'string?
                                       :doc "A message sent to each user that joins the connected workspace (slack team). It should prompt the user to connect their account."}
   :slack.team/name {:spark/schema 'string?}
   :slack.team/invite-link {:spark/schema 'string?
                            :doc "Invitation link that allows a new user to sign up for a Slack team. (Typically expires every 30 days.)"}
   :slack.team/slack.app {:doc "Slack app connected to this team (managed by Sparkboard)"}

   :slack.user/firebase-account (ref :one #{:firebase-account/id})


   :social-feed.twitter/hashtags {:spark/schema [:set 'string?]}
   :social-feed.twitter/mentions {:spark/schema [:set 'string?]}
   :social-feed.twitter/profiles {:spark/schema [:set 'string?]}

   :spark/created-at {:spark/schema 'inst?
                      :doc "Auto-generated creation-date of entity"},
   :spark/created-by (merge (ref :one #{:member/id})
                            {:doc "Member who created this entity"}),

   :spark/deleted? {:spark/schema 'boolean?, :doc "Deletion flag", :todo "Excise deleted data after a grace period"},

   :spark/domain (merge (ref :one #{:domain/name})
                        {:doc "Domain name linked to this entity"}),

   :spark/id-key {:doc "Refers to the primary key of the entity"
                  :unsure "Should this key exist in the db, or only during migration etc.?"}

   :spark/last-modified-by (ref :one #{:member/id}),

   :spark/role {:doc "A keyword representing a role which may be granted to a member",
                :spark/schema [:enum :admin :project/editor :project/owner]},

   :spark/slack.team {:doc "Metadata for a Slack team. Links a slack team, slack app, and sparkboard."},
   :spark/slack.user {:doc "Metadata for a Slack user. Links a slack user, slack team, and firebase account."},
   :spark/social-feed {:doc "Settings for a live feed of social media related to an entity"},

   :spark/title {:spark/schema 'string?},

   :spark/updated-at {:spark/schema 'inst?},

   :spark.locales/default {:spark/schema 'string?},
   :spark.locales/dictionary {:doc "Extra/override translations, eg. {'fr' {'hello' 'bonjour'}}",
                              :spark/schema [:map-of :spark.locales/name :spark.locale/dictionary]},
   :spark.locales/name {:doc "2-letter locale name, eg. 'en', 'fr'", :spark/schema [:re #"[a-z]{2}"]},
   :spark.locales/suggested {:doc "Suggested locales (set by admin, based on expected users)",
                             :spark/schema [:vector :spark.locales/name]},

   :tag/background-color {},
   :tag/label {},
   :tag/locked {:doc "Tag may only be modified by an admin of the owner of this tag"},
   :tag/owner (merge (ref :one #{:board/id})
                     {:doc "The entity which manages this tag",}),
   :thread/members {:doc "Set of participants in a thread."},
   :thread/messages {:doc "List of messages in a thread.", :order-by :spark/created-at},
   :thread/read-by (merge (ref :many #{:member/id})
                          {:doc "Set of members who have read the most recent message.",
                           :todo "Map of {member, last-read-message} so that we can show unread messages for each member."})

   :board/labels {:unsure "How can this be handled w.r.t. locale?"}

   :org.board/template-default {:doc "Default template (a board with :board/is-template? true) for new boards created within this org"}
   :org.board/show-org-tab? {:doc "When true, boards should visually link to the parent organization (in main nav)"}
   :org/public? {:doc "When true, org should have a public page (which lists public boards and projects)"}

   :notification/viewed? {:doc "The notification has been viewed by the user"
                          :todo "deprecate: log {:notifications/viewed-notifications-at _} per member"}
   :notification/subject-viewed? {:doc "The subject of a notification has been viewed by the user"
                                  :todo "deprecate: log {:notifications/viewed-subject-at _} per [member, subject] pair"}
   :notification/emailed? {:doc "The notification has been included in an email"
                           :todo "deprecate: log {:notifications/emailed-at _} per member"}
   :notification/subject (merge (ref :one #{:project/id
                                            :thread/id
                                            :post/id})
                                {:doc "The primary entity referred to in the notification (when viewed"})
   :notification/type {:spark/schema [:enum
                                      :notification.type/new-project-member
                                      :notification.type/new-private-message
                                      :notification.type/new-discussion-post
                                      :notification.type/new-post-comment]}
   :notification/recipient (ref :one #{:member/id})
   :message/text {}
   :member.admin/inactive? {:doc "Marks a member inactive, hidden."
                            :todo "If an inactive member signs in to a board, mark as active again."}
   :member.admin/suspected-fake? {}
   :member/newsletter-subscription? {:spark/schema 'boolean?}
   :member/new? {:spark/schema 'boolean?}
   :member/name {:spark/schema 'string?}
   :member/not-joining-a-project {:doc "Member has indicated they are not looking to join a project."}
   :html/css {:doc "CSS styles (a string) relevant to a given entity"
              :todo "Deprecate in favour of defined means of customization"}
   :html.meta/description {}
   :image-url/background {:spark/schema :http/url},
   :image-url/footer {:spark/schema :http/url},
   :image-url/logo {:spark/schema :http/url},
   :image-url/logo-large {:spark/schema :http/url},
   :image-url/sub-header {:spark/schema :http/url},
   :action/project.add {:spark/schema :action/policy}
   :action/policy {:spark/schema [:map :action/requires-role]}
   :action/requires-role {:spark/schema [:set :spark/role]}
   :member/image-url {}
   :slack-user/firebase-account (ref :one #{:firebase-account/id})
   :member/firebase-account (ref :one #{:firebase-account/id})
   :member/accepted-terms? {:spark/schema 'boolean?}
   :member/email-frequency {:spark/schema [:enum :daily :instant :periodic :never]}

   })

;; previously used - relates to :notification/subject-viewed?
(def notification-subjects {:notification.type/new-project-member :project/id
                            :notification.type/new-private-message :thread/id
                            :notification.type/new-discussion-post :post/id
                            :notification.type/new-post-comment :post/id})

(register! (update-vals extra-schema :spark/schema))

(gen :action/policy)