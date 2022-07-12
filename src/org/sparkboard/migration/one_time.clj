(ns org.sparkboard.migration.one-time
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :as str]
            [jsonista.core :as json]
            [malli.provider :as mp]
            [org.sparkboard.server.env :as env]
            [clojure.instant :as inst]
            [clojure.walk :as walk]
            [clojure.set :as set]
            [re-db.schema :as schema]
            [tools.sparkboard.util :as u]
            [clojure.pprint :refer [pprint]]
            [java-time :as t]
            [org.sparkboard.schema :as sschema :refer [gen]]
            [malli.registry :as mr]
            [malli.transform :as mt])
  (:import java.lang.Integer
           [java.util Date]))

;; For exploratory or one-off migration steps.
;;
;; The `mongoexport` command is required.
;; MacOS: brew tap mongodb/brew && brew install mongodb-community
;; Alpine: apk add --update-cache mongodb-tools
;;



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TIME

(defn date-time [inst] (t/local-date-time inst "UTC"))

(defn bson-id-timestamp [id]
  (new Date (* 1000 (java.lang.Integer/parseInt (subs id 0 8) 16))))

(defn timestamp-from-id
  ([] (timestamp-from-id :spark/created-at))
  ([to-k]
   (fn [m a v]
     (assoc m to-k (bson-id-timestamp (:$oid v v))
              a (:$oid v v)))))

(defn days-between
  ([dt] (days-between dt (t/local-date-time)))
  ([date-time-1 date-time-2]
   (t/as (t/duration (t/truncate-to date-time-1 :days)
                     (t/truncate-to date-time-2 :days)) :days)))

(defn date-time [inst] (t/local-date-time inst "UTC"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DB

(def MONGODB_URI (-> env/config :prod :mongodb/readonly-uri))

(def mongo-colls {:member "users"
                  :notification "notificationschemas"
                  :project "projectschemas"
                  :discussion "discussionschemas"
                  :thread "threadschemas"})

(def firebase-colls {:org "org"
                     :board "settings"
                     :domain "domain"
                     :collection "collection"
                     :grants "roles"
                     :slack.user "slack-user"
                     :slack.team "slack-team"
                     :slack.broadcast "slack-broadcast"
                     :slack.channel "slack-channel"})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Transformation

(def schema {:spark/deleted? (merge schema/boolean
                                    schema/one)
             :project/members (merge schema/many
                                     schema/ref)})

(defn & [f & fs]
  (if (seq fs)
    (recur (fn [m a v]
             (let [m (f m a v)
                   v (m a)]
               ((first fs) m a v)))
           (rest fs))
    f))

(defn some-value [x]
  (cond (nil? x) x
        (coll? x) (u/guard x (complement empty?))
        (string? x) (u/guard x (complement str/blank?))
        :else x))

(defn assoc-some-value [m a v]
  (if-some [v (some-value v)]
    (assoc m a v)
    (dissoc m a)))

(defn xf [f & args]
  (fn [m a v] (assoc-some-value m a (apply f v args))))

(defn rm [m a _] (dissoc m a))

(defn rename [a]
  (fn [m pa v]
    (-> m
        (dissoc pa)
        (assoc-some-value a v)
        (cond-> (= "id" (name a))
                (assoc :spark/id-key a)))))

(defn lookup-ref [k]
  (xf (fn lf [v]
        (if (or (set? v) (sequential? v))
          (map lf v)
          (when-some [v (:$oid v v)]
            (assert (string? v))
            [k v])))))

(defn kw-keys [m]
  (walk/postwalk (fn [m]
                   (if (map? m)
                     (update-keys m keyword)
                     m)) m))

(defn fire-flat
  ([id-key m]
   (mapv (fn [[id m]] (assoc m id-key id :spark/id-key id-key)) m))
  ([m] (fire-flat :firebase/id m)))

(defn change-keys
  ([changes] (keep (partial change-keys changes)))
  ([changes doc]
   (let [changes (->> changes flatten (keep identity) (partition 2))
         update-map (->> changes
                         (filter #(= ::update-map (first %)))
                         (map second)
                         (apply comp))
         changes (remove #(= ::update-map (first %)) changes)
         walk (comp #(walk/prewalk (fn [x]
                                     (if (map? x)
                                       (reduce (fn [m [a f]]
                                                 (if (= a ::always)
                                                   (f m)
                                                   (if-some [v (some-value (m a))]
                                                     (f m a v)
                                                     (dissoc m a))))
                                               x
                                               changes)
                                       x)) %)
                    update-map)]
     (if (sequential? doc)
       (keep walk doc)
       (walk doc)))))

(defn unmunge-domain [s] (str/replace s "_" "."))

(defn parse-sparkboard-id [s]
  (let [[_ etype eid] (re-find #"sparkboard[._]([^:]+):(.*)" s)]
    [(keyword ({"user" "member"} etype etype) "id") eid]))

(defn lift-sparkboard-id [m a v]
  (let [[k id] (parse-sparkboard-id v)]
    (-> m (dissoc a) (assoc k id))))

(comment
 (map parse-sparkboard-id ["sparkboard.org:x" "sparkboard_board:-abc"]))

(defn smap [m] (apply sorted-map (apply concat m)))

(defn parse-$date [d] (t/instant (inst/read-instant-timestamp d)))

(def read-firebase
  (memoize
   (fn [] (->> (slurp (env/db-path "firebase.edn")) (read-string)))))

(def read-coll
  (memoize
   (fn [k]
     (cond (mongo-colls k)
           (read-string (slurp (env/db-path (str (name k) ".edn"))))
           (firebase-colls k) (cond->> ((read-firebase) (firebase-colls k))
                                      #_#_(= k :board)
                                      (merge-with merge ((read-firebase) "privateSettings")))
           :else (throw (ex-info (str "Unknown coll " k) {:coll k}))))))


(def handle-image-urls (xf (fn [urls]
                             (set/rename-keys urls {"logo" :image-url/logo
                                                    "logoLarge" :image-url/logo-large
                                                    "footer" :image-url/footer
                                                    "background" :image-url/background
                                                    "subHeader" :image-url/sub-header}))))

(defn id-key
  ([k] (fn [m] (id-key m k)))
  ([m k] (when m
           (if (map? m)
             (assoc m :spark/id-key k)
             (map #(assoc % :spark/id-key k) m)))))

(defn parse-field-type [t]
  (case t "image" :field.type/image
          "video" :field.type/video
          "select" :field.type/select
          "linkList" :field.type/link-list
          "textarea" :field.type/content
          :field.type/content))

(def all-field-types
  (->> (read-coll :board)
       fire-flat
       (mapcat (juxt #(% "groupFields") #(% "userFields")))
       (mapcat identity)
       (map (fn [[id {:as f :strs [type]}]] [id (parse-field-type type)]))
       (into {"problem" :field.type/content
              "solution" :field.type/content
              "intro" :field.type/content
              "about_me" :field.type/content

              ;; documented ignored fields
              "description" nil ;; admin-set description is handled in parse step
              "about" nil
              "contact" nil ;; remove old 'contact' fields which often contained an email address or phone number
              "badges" nil
              "role" nil
              "department" nil
              })))

(defn parse-fields [target-k]
  (fn [m]
    (if-some [field-ks (->> (keys m)
                            (filter #(str/starts-with? (name %) "field_"))
                            seq)]
      (reduce (fn [m k]
                (let [field-spec-id (subs (name k) 6)
                      target-id (m target-k)
                      v (m k)
                      field-type (all-field-types field-spec-id)
                      ;; NOTE - we ignore fields that do not have a spec
                      field-value (when field-type
                                    (case field-type
                                      :field.type/image {:field.image/image-url v}
                                      :field.type/link-list (mapv #(set/rename-keys % {:label :field.link-list/label
                                                                                       :url :field.link-list/url}) v)
                                      :field.type/select {:field.select/value v}
                                      :field.type/content {:field.content/format :html
                                                           :field.content/html v}
                                      :field.type/video (when-not (str/blank? v)
                                                          (cond (re-find #"vimeo" v) {:field.video/format :video.format/vimeo-url
                                                                                      :field.video/url v}
                                                                (re-find #"youtube" v) {:field.video/format :video.format/youtube-url
                                                                                        :field.video/url v}
                                                                :else {:field.video/format :video.format/youtube-id
                                                                       :field.video/youtube-id v}))
                                      (throw (Exception. (str "Field type not found "
                                                              {:field/type field-type
                                                               :field-spec/id field-spec-id
                                                               })))))]
                  (-> (dissoc m k)
                      (cond-> field-value
                              (update
                               :field/_target
                               ;; question, how to have uniqueness
                               ;; based on tuple of [field-spec/id, field/target]
                               (fnil conj [])
                               (merge
                                field-value
                                {:field/type field-type
                                 :field/id (str target-id ":" field-spec-id)
                                 :field/parent [target-k target-id]
                                 :field/spec [:field-spec/id field-spec-id]}
                                ))))))
              m
              field-ks)
      m)))



(def changes {:board
              [::update-map (partial fire-flat :board/id)
               "groupNumbers" (rename :board.project/show-numbers?)
               "projectNumbers" (rename :board.project/show-numbers?)
               "userMaxGroups" (rename :board.member/max-projects)
               "stickyColor" (rename :design/sticky-color)
               "tags" (& (fn [m a v]
                           (assoc m a (->> v
                                           (fire-flat :tag/id)
                                           (sort-by :tag/id)
                                           (mapv #(-> %
                                                      (assoc :tag/owner [:board/id (:board/id m)])
                                                      (dissoc "order")
                                                      (set/rename-keys {"color" :tag/background-color
                                                                        "name" :tag/label
                                                                        "label" :tag/label
                                                                        "restrict" :tag/locked})
                                                      (u/update-some {:tag/locked (constantly true)}))))))
                         (rename :board.member/tags))
               "social" (& (xf (fn [m] (into #{} (map keyword) (keys m)))) (rename :board/project-sharing-buttons))
               "userMessages" (rename :board.member/private-messaging?)
               "groupSettings" rm
               "registrationLink" (rename :board.registration/link-override)
               "slack" (& (xf #(% "team-id")) (lookup-ref :slack/team-id) (rename :board/slack.team))
               "registrationOpen" (rename :board.registration/open?) ;; what does this mean exactly
               "images" (& handle-image-urls
                           (rename :board/image-urls))

               (let [field-xf (fn [m a v]
                                (assoc m a
                                         (try (->> v
                                                   (fire-flat :field-spec/id)
                                                   (sort-by #(% "order"))
                                                   (mapv (fn [m]
                                                           (-> m
                                                               (set/rename-keys {"type" :field-spec/type
                                                                                 "showOnCard" :field-spec/show-on-card?
                                                                                 "showAtCreate" :field-spec/show-at-create?
                                                                                 "showAsFilter" :field-spec/show-as-filter?
                                                                                 "required" :field-spec/required?
                                                                                 "hint" :field-spec/hint
                                                                                 "id" :field-spec/id
                                                                                 "label" :field-spec/label
                                                                                 "options" :field-spec/options
                                                                                 "order" :field-spec/order
                                                                                 "name" :field-spec/name})
                                                               (u/update-some {:field-spec/field-type parse-field-type
                                                                               :field-spec/options (partial mapv #(update-keys % (fn [k]
                                                                                                                                   (case k "label" :field-spec.option/label
                                                                                                                                           "value" :field-spec.option/value
                                                                                                                                           "color" :field-spec.option/color
                                                                                                                                           "default" :field-spec.option/default?))))})
                                                               (dissoc :field-spec/name)
                                                               (assoc :field-spec/owner [:board/id (:board/id m)])))))
                                              (catch Exception e (prn a v) (throw e)))))]
                 ["groupFields" (& field-xf (rename :board.project/fields))
                  "userFields" (& field-xf (rename :board.member/fields))])
               "userLabel" (& (fn [m a [singular plural]]
                                (update m :board/labels merge {:label/member.one singular
                                                               :label/member.many plural})) rm)
               "groupLabel" (& (fn [m a [singular plural]]
                                 (update m :board/labels merge {:label/project.one singular
                                                                :label/project.many plural})) rm)
               "createdAt" (& (xf t/instant) (rename :spark/created-at))
               "publicVoteMultiple" rm

               "descriptionLong" rm ;;  last used in 2015

               "description" (rename :board.landing-page/description) ;; if = "Description..." then it's never used
               "publicWelcome" (rename :board.landing-page/instructions)

               "css" (rename :html/css)
               "projectsRequireApproval" (& (xf #(when % :any-admin))
                                            (rename :board.project/requires-approval?))
               "parent" (& (xf parse-sparkboard-id)
                           (rename :board/org))
               "authMethods" rm
               "allowPublicViewing" (rename :board/public?)
               "communityVoteSingle" (fn [m a v]
                                       (-> m
                                           (dissoc a)
                                           (assoc :community-vote/_board #{{:community-vote/board [:board/id (:board/id m)]
                                                                            :community-vote/open? v}})))

               "newsletterSubscribe" (rename :board.member/newsletter-checkbox?)
               "groupMaxMembers" (rename :board.project/max-members)
               "headerJs" (rename :html/js)
               "projectTags" rm
               "registrationEmailBody" (rename :board.registration/invitation-email-body)
               "learnMoreLink" (rename :board.landing-page/learn-more-url)
               "metaDesc" (rename :html.meta/description)
               "isTemplate" (rename :board/is-template?)
               "registrationMessage" (rename :board.registration/welcome-message-body)
               "defaultFilter" rm
               "defaultTag" rm
               "locales" (rename :spark.locales/dictionary)
               "filterByFieldView" rm ;; deprecated - see :field/show-as-filter?
               "permissions" (& (xf (constantly
                                     {:action/project.add {:action/requires-role #{:admin}}}))
                                (rename :board/policies))
               "languages" (& (xf (partial mapv #(get % "code"))) (rename :spark.locales/suggested))
               ]
              :org [::update-map (partial fire-flat :org/id)
                    "allowPublicViewing" (rename :org/public?)
                    "images" (& handle-image-urls
                                (rename :org/image-urls))
                    "showOrgTab" (rename :org.board/show-org-tab?)
                    "creator" (& (lookup-ref :member/id)
                                 (rename :spark/created-by))
                    "boardTemplate" (& (lookup-ref :board/id)
                                       (rename :org.board/template-default))]
              :slack.user [::update-map (partial fire-flat :slack.user/id)
                           "account-id" (& (lookup-ref :firebase-account/id)
                                           (rename :slack.user/firebase-account)) ;; should account-id become spark/member-id?
                           "team-id" (& (lookup-ref :slack.team/id)
                                        (rename :slack.user/slack.team))]
              :slack.team [::update-map (partial fire-flat :slack.team/id)
                           "board-id" (& (lookup-ref :board/id)
                                         (rename :slack.team/board))
                           "account-id" (& (lookup-ref :firebase-account/id)
                                           (rename :member/firebase-account))
                           "team-id" (rename :slack.team/id)
                           "team-name" (rename :slack.team/name)
                           "invite-link" (rename :slack.team/invite-link)
                           "bot-user-id" (rename :slack.app/bot-user-id)
                           "bot-token" (rename :slack.app/bot-token)
                           "custom-messages" (& (xf (fn [m] (set/rename-keys m {"welcome" :slack.team.custom-message/welcome}))) (rename :slack.team/custom-messages))
                           "app" (& (xf (fn [app]
                                          (->> app (fire-flat :slack.app/id)
                                               (change-keys ["bot-user-id" (rename :slack.app/bot-user-id)
                                                             "bot-token" (rename :slack.app/bot-token)])
                                               first)))
                                    (rename :slack.team/slack.app))]
              :slack.broadcast [::update-map (partial fire-flat :slack.broadcast/id)
                                "replies" (& (xf (comp (partial change-keys ["message" (rename :slack.broadcast.reply/text)
                                                                             "user-id" (& (lookup-ref :slack.user/id)
                                                                                          (rename :slack.broadcast.reply/slack.user))
                                                                             "channel-id" (& (lookup-ref :slack.channel/id)
                                                                                             (rename :slack.broadcast.reply/slack.channel))
                                                                             "from-channel-id" rm])
                                                       (partial filter #(seq (% "message")))
                                                       (partial fire-flat :slack.broadcast.reply/id)))
                                             (rename :slack.broadcast/slack.broadcast.replies))
                                "message" (rename :slack.broadcast/text)
                                "user-id" (& (lookup-ref :slack.user/id) (rename :slack.broadcast/slack.user))
                                "team-id" (& (lookup-ref :slack.team/id) (rename :slack.broadcast/slack.team))
                                "response-channel" (& (lookup-ref :slack.channel/id) (rename :slack.broadcast/response-channel))
                                "response-thread" (& (lookup-ref :slack.thread/id) (rename :slack.broadcast/response-thread))
                                ]
              :slack.channel [::update-map (partial fire-flat :slack.channel/id)
                              "project-id" (& (lookup-ref :project/id)
                                              (rename :slack.channel/project))
                              "team-id" (& (lookup-ref :slack.team/id)
                                           (rename :slack.channel/slack.team))]
              :domain [::update-map (partial mapv (fn [[name target]]
                                                    {:spark/id-key :domain/name
                                                     :domain/name (unmunge-domain name)
                                                     :domain/target (parse-sparkboard-id target)}))]
              :collection [::update-map (partial fire-flat :collection/id)
                           "images" (& handle-image-urls
                                       (rename :collection/image-urls))
                           "boards" (& (xf (fn [m] (into [] (comp (filter val) (map key)) m))) (rename :collection/boards)) ;; ordered list!

                           ]
              :board/private [::update-map (partial fire-flat :board/id)
                              "registrationCode" (& (xf (fn [code] (when-not (str/blank? code)
                                                                     {code {:registration-code/active? true}} #{code})))
                                                    (rename :board/registration-codes))
                              "webHooks" (rename :board/webhooks)
                              "updateMember" (& (xf (partial hash-map :webhook/url)) (rename :event.board/update-member))
                              "newMember" (& (xf (partial hash-map :webhook/url)) (rename :event.board/new-member))
                              ]
              :grants [::update-map
                       (fn [{:strs [e-u-r]}]
                         (into [] (mapcat
                                   (fn [[ent user-map]]
                                     (for [[user role-map] user-map
                                           :let [[_ entity-id :as entity-ref] (parse-sparkboard-id ent)
                                                 [_ member-id :as member-ref] (parse-sparkboard-id user)
                                                 _ (assert (and (string? entity-id) (string? member-id)))]]
                                       {:spark/id-key :grant/id
                                        :grant/id (str entity-id ":" member-id)
                                        :grant/entity entity-ref
                                        :grant/member member-ref
                                        :grant/roles (into #{} (comp (filter val)
                                                                     (map key)
                                                                     (map keyword)) role-map)})))
                               e-u-r))]
              ::firebase ["localeSupport" (rename :spark.locales/suggested)
                          "languageDefault" (rename :spark.locales/default)
                          "socialFeed" (rename :spark/social-feed)
                          "twitterHashtags" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/hashtags))
                          "twitterProfiles" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/profiles))
                          "twitterMentions" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/mentions))
                          "domain" (& (lookup-ref :domain/name) (rename :spark/domain))
                          "title" (rename :spark/title)]

              :member [:salt rm
                       :hash rm
                       :passwordResetToken rm
                       :email rm ;; in account
                       :account rm
                       :_id (& (timestamp-from-id)
                               (rename :member/id))
                       ::always (parse-fields :member/id)
                       :account rm

                       :boardId (& (lookup-ref :board/id)
                                   (rename :member/board))

                       :firebaseAccount (& (lookup-ref :firebase-account/id)
                                           (rename :member/firebase-account))

                       :emailFrequency (& (xf #(keyword (or % "periodic")))
                                          (rename :member/email-frequency))
                       :acceptedTerms (rename :member/accepted-terms?)
                       :contact_me rm

                       :first_time (rename :member/new?)
                       :ready (rename :member/not-joining-a-project)
                       ;; new feature - not-joining-a-project


                       :name (rename :member/name)
                       :roles (& (fn [m a roles]
                                   (assoc m a (when (seq roles)
                                                [{:grant/member (:member/id m)
                                                  :grant/entity (:member/board m)
                                                  :grant/roles (into #{} (map keyword) roles)}])))
                                 (rename :grant/_member))
                       :tags (& (xf (partial hash-map :tag/id)) (rename :member/tags))

                       :newsletterSubscribe (rename :member/newsletter-subscription?)
                       :active (& (xf not) (rename :member.admin/inactive?)) ;; same as deleted?
                       :picture (rename :member/image-url)
                       :votesByDomain (& (fn [m a votes]
                                           (assoc m a
                                                    (mapv (fn [[domain id]]
                                                            {:community-vote/member (:member/id m)
                                                             :community-vote/domain [:domain/name (unmunge-domain (name domain))]
                                                             :community-vote/project [:project/id id]}) votes)))
                                         (rename :community-vote/_member))
                       :suspectedFake (rename :member.admin/suspected-fake?)

                       :feedbackRating rm

                       ]
              :discussion [::update-map (fn [m]
                                          (when-not (empty? (:posts m))
                                            m))
                           :_id (& (timestamp-from-id)
                                   (rename :discussion/id))
                           :type rm
                           :user (& (lookup-ref :member/id)
                                    (rename :spark/created-by))
                           :text (& (xf (fn [s] {:content/format :html
                                                 :content/html s})) (rename :post/content))
                           :followers (& (lookup-ref :member/id)
                                         (rename :discussion/followers))
                           :doNotFollow (& (lookup-ref :member/id)
                                           (rename :post/do-not-follow))
                           :parent (& (lookup-ref :project/id)
                                      (rename :discussion/project))
                           :posts (& (xf (partial change-keys [:followers (& (lookup-ref :member/id)
                                                                             (rename :post/followers))]))
                                     (rename :discussion/posts))
                           :board/_discussion rm
                           :discussion/posts (xf (partial change-keys [:_id (rename :post/id)
                                                                       :parent rm]))
                           :comments (& (xf (partial change-keys
                                                     [:post/id (& (timestamp-from-id)
                                                                  (rename :comment/id))
                                                      :user (rename :spark/created-by)
                                                      :text (rename :comment/body)
                                                      :parent rm]))
                                        (rename :post/comments))
                           :boardId (& (lookup-ref :board/id)
                                       (rename :discussion/board))]
              :project [:_id (& (timestamp-from-id)
                                (rename :project/id))
                        :field_description (& (xf (fn [s] {:content/format :html :content/html s}))
                                              (rename :project.admin/description))
                        ::always (parse-fields :project/id)
                        :boardId (& (lookup-ref :board/id)
                                    (rename :project/board))
                        :user_id (& (lookup-ref :member/id)
                                    (rename :membership/member))
                        :tags rm ;; no longer used - fields instead
                        :number (rename :project.admin/board.number)
                        :badges (& (xf (partial mapv (partial hash-map :badge/label)))
                                   (rename :project.admin/badges)) ;; should be ref
                        :active (& (xf not) (rename :project.admin/inactive?))
                        :approved (rename :project.admin/approved?)
                        :ready (rename :project/viable-team?)
                        :members (rename :project/members)
                        :looking_for (& (xf (fn [ss] (mapv (partial hash-map :request/text) ss)))
                                        (rename :project/open-requests))
                        :sticky (rename :project.admin/sticky?)
                        :demoVideo (rename :project/video-url)
                        :discussion rm ;; unused
                        ]
              :notification [::update-map (fn [m]
                                            (let [day-threshold 180] ;; remove notifications older than this
                                              (when (<= (-> m :createdAt :$date parse-$date date-time days-between)
                                                        day-threshold)
                                                ;; when older than 60 days, remove
                                                m)))
                             :_id (& (timestamp-from-id)
                                     (rename :notification/id))
                             :createdAt rm
                             :boardId (& (lookup-ref :board/id)
                                         (rename :notification/board))
                             :recipientId (& (lookup-ref :member/id)
                                             (rename :notification/recipient))

                             :targetViewed (rename :notification/subject-viewed?)
                             :notificationViewed (rename :notification/viewed?)
                             :notificationEmailed (rename :notification/emailed?)
                             :data (fn [m a v] (-> m (dissoc a) (merge v)))
                             :project (& (xf :id)
                                         (lookup-ref :project/id)
                                         (rename :notification/project))
                             :user (& (xf :id)
                                      (lookup-ref :member/id)
                                      (rename :notification/member))
                             :message (& (xf :body)
                                         (rename :notification/message)
                                         )
                             :comment (& (xf :id)
                                         (lookup-ref :comment/id)
                                         (rename :notification/comment))
                             :post (& (xf :id)
                                      (lookup-ref :post/id)
                                      (rename :notification/post))
                             :discussion (& (xf :id)
                                            (lookup-ref :discussion/id)
                                            (rename :notification/discussion))
                             ::always (fn [m]
                                        (let [{:keys [type targetId]} m]
                                          (-> m
                                              (dissoc :type :targetId :targetPath)
                                              (merge (case type
                                                       "newMember" {:notification/type :notification.type/new-project-member}
                                                       "newMessage" {:notification/type :notification.type/new-private-message
                                                                     :notification/thread [:thread/id targetId]}
                                                       "newPost" {:notification/type :notification.type/new-discussion-post}
                                                       "newComment" {:notification/type :notification.type/new-post-comment})))))
                             :notification/board rm

                             ]
              :thread [:_id (& (timestamp-from-id)
                               (rename :thread/id))
                       :participantIds (& (xf set)
                                          (lookup-ref :member/id)
                                          (rename :thread/members))
                       :createdAt (rename :spark/created-at)
                       :readBy (& (xf #(into #{} (map (fn [k] [:member/id (name k)])) (keys %))) (rename :thread/read-by)) ;; change to a set of has-unread?
                       :modifiedAt (rename :spark/updated-at)

                       ;; TODO - :messages
                       :messages (& (xf (partial change-keys [:_id (rename :message/id)
                                                              :body (rename :message/text)]))
                                    (rename :thread/messages))
                       :senderId (& (lookup-ref :member/id)
                                    (rename :spark/created-by))
                       :senderData rm
                       :boardId rm #_(& (lookup-ref :board/id)
                                        (rename :thread/board))]
              ::mongo [:$oid (fn [m a v] (reduced v))
                       :$date (fn [m a v] (reduced (parse-$date v)))
                       :deleted (rename :spark/deleted?)
                       :updatedAt (rename :spark/updated-at)
                       :title (rename :spark/title)
                       :intro (rename :project/summary)
                       :owner (rename :spark/created-by)
                       :lastModifiedBy (& (lookup-ref :member/id)
                                          (rename :spark/last-modified-by))

                       :htmlClasses (fn [m k v]
                                      (let [classes (str/split v #"\s+")]
                                        (-> m
                                            (dissoc k)
                                            (u/assoc-some :html/card-classes (some-> (remove #{"sticky"} classes)
                                                                                     seq
                                                                                     (str/join " ")))
                                            (cond->
                                             (some #{"sticky"} classes)
                                             (assoc :project.admin/sticky? true)))))


                       #_#_:_id (& (xf #(:$oid % %))
                                   (fn [m a id] (assoc m :spark/created-at (bson-id-timestamp id)))
                                   (rename (keyword (name coll) "id")))
                       :__v rm
                       :boardIds rm
                       :votes rm
                       :links rm
                       :boardMemberships rm
                       :publicVoteCount rm
                       :originalBoardId rm]})
;; account, :boardId, :discussion
(defn fetch-mongodb []
  (doseq [[coll-k mongo-coll] mongo-colls
          :let [_ (prn :starting coll-k)
                {:keys [out err exit]} (sh "mongoexport"
                                           "--uri" MONGODB_URI
                                           "--jsonArray"
                                           "--collection" mongo-coll)
                _ (prn :downloaded coll-k)
                clj (->> (json/read-value out json/keyword-keys-object-mapper)
                         #_(mapv parse-mongo-doc))
                _ (prn :parsed coll-k)]]
    (when-not (zero? exit)
      (throw (Exception. err)))
    (spit (env/db-path (str (name coll-k) ".edn")) clj)))

(defn fetch-firebase []
  (let [{token :firebase/database-secret
         {db :databaseURL} :firebase/app-config} (:prod env/config)]
    (->> (str db "/.json?auth=" token)
         (slurp)
         (json/read-value)
         (spit (env/db-path "firebase.edn")))))

(defn parse-coll [k]
  (->> (read-coll k)
       (change-keys (into (changes (cond (mongo-colls k) ::mongo
                                         (firebase-colls k) ::firebase
                                         :else (throw (ex-info (str "Unknown coll: " k) {:coll k}))))
                          (changes k)))))

(defn collect-schemas [s]
  (let [!out (atom #{})]
    (walk/postwalk (fn [x]
                     (if (and (vector? x)
                              (u/guard (first x) (every-pred some? keyword? (comp boolean namespace))))
                       (let [opt? (map? (second x))]
                         (swap! !out conj (if opt?
                                            (concat (take 1 x) (drop 2 x))
                                            x))
                         (first x))
                       x))
                   s)
    @!out))

(defn add-merge [attrs pred m]
  (reduce-kv (fn [m k v]
               (cond-> m
                       (pred k)
                       (update k merge (if (fn? attrs)
                                         (attrs k)
                                         attrs)))) m m))

(defn pluralize [s] (if (str/ends-with? s "y")
                      (str (subs s 0 (dec (count s))) "ies")
                      (str s "s")))

(defn infer-schemas []
  (->> (concat (keys mongo-colls) (keys firebase-colls))
       (into [] (map (juxt #(keyword "entity" (name %))
                           (comp (mp/provider {::mp/map-of-threshold 10})
                                 #(take 200 %)
                                 shuffle
                                 parse-coll))))
       collect-schemas
       (mapcat (fn [[k v]] (when-not (= \_ (first (name k))) [k (sschema/S v)])))
       (apply sorted-map)))

(defonce inferred-schemas (delay (infer-schemas)))

(defn register-schemas! [s]
  (sschema/register! (reduce-kv (fn [m a {:keys [spark/schema]}] (assoc m a schema)) {} @inferred-schemas))
  s)

(defn singularize [n]
  (cond (str/ends-with? n "ies") (str (subs n 0 (- (count n) 3)) "y")
        (str/ends-with? n "s") (subs n 0 (dec (count n)))
        :else n))

#_(defn tags [k]
    (let [ns (namespace k)
          ns-tag (if (= ns "spark")
                   (keyword (name k))
                   (keyword (first (str/split (namespace k) #"\."))))]
      (cond-> #{ns-tag}
              (types (name k))
              (conj (keyword (name k))))))

(defn get-types [] (->> @inferred-schemas keys (filter (comp #{"id"} name)) (map namespace) set))

(defn compute-schemas []
  (let [types (get-types)
        namespaces (into #{} (map (comp keyword namespace)) (keys @inferred-schemas))
        plural-types (into #{} (map pluralize) types)]
    (->> @inferred-schemas
         (add-merge (fn [k]
                      (sschema/ref :one #{(keyword (name k) "id")})) (every-pred (comp types name)
                                                                                 (comp (complement #{"entity"}) namespace)))
         (add-merge (fn [k]
                      (sschema/ref :many #{(keyword (singularize (name k)) "id")})) (comp plural-types name))
         (add-merge sschema/unique-string-id (comp #{"id"} name))
         (add-merge {:spark/schema [:set :spark/role]} (comp #{"requires-role"} name))
         (add-merge sschema/bool (comp #{\?} last name))
         (add-merge sschema/http-url (fn [k]
                                       (or (re-find #".*\burl$" (name k))
                                           (some->> (namespace k) (re-find #".*\burl$")))))
         (add-merge sschema/html-color (comp #{"color"} name))
         (add-merge #(sschema/extra-schema %) sschema/extra-schema)
         ;; merge extra-schema from sschema
         (into {})
         (register-schemas!)
         #_(#(update-vals % :spark/schema))
         (into (sorted-map)))))


(comment
 (-> (fetch-mongodb) (clojure.core/time))
 (-> (fetch-firebase) (clojure.core/time))

 (defn g [s] (fn [m] (m s)))

 (def inferred-schemas (atom (infer-schemas)))
 (->> (compute-schemas)
      ;;
      (remove (comp #{"label"} namespace key))
      (remove (comp (into #{"image-urls"}
                          (map pluralize (get-types))) name key))
      (remove (comp (set (concat (keys sschema/extra-schema)
                                 (map (fn [t] (keyword t "id")) (get-types)))) key))
      (remove (comp #{:db/lookup-ref} :spark/schema val))
      (into (sorted-map))
      )
 (->> (parse-coll :member)
      (into #{} (map :member/email-frequency)))
 (->> (read-coll :project)
      (keep :role)
      count)


 (def missing-specs (->> (concat (read-coll :project)
                                 (read-coll :member))
                         (mapcat keys)
                         distinct
                         (map name)
                         (keep #(when (str/starts-with? % "field_")
                                  (subs % 6)))
                         (remove #(contains? all-field-types %))
                         (map (comp keyword (partial str "field_")))
                         (into #{})))

 (all-field-types "-LCYCO2FqUvkyy_Ui2OA")
 (do ;def entities-containing-missing-field
   (let [board-titles (-> (read-coll :board)
                          (update-vals #(get % "title")))]
     (->> (concat (read-coll :project)
                  (read-coll :member))
          (reduce (fn [out x]
                    (if-let [ks (seq (filter (partial contains? missing-specs) (keys x)))]
                      (update out (board-titles (x :boardId)) (fnil into #{}) ks)
                      out)) {})


          )))

 (def board-by-title (->> (read-coll :board)
                          fire-flat
                          (group-by #(% "title"))
                          (#(update-vals % first))))

 (defn board-by [k v]
   (->> (read-coll :board)
        fire-flat
        (filter #(= v (get % k)))))


 (def board-by-title (->> (read-coll :board)
                         fire-flat
                         (group-by #(% "title"))
                          (#(update-vals % first))))

 (for [t [:notification.type/new-project-member
          :notification.type/new-private-message
          :notification.type/new-discussion-post
          :notification.type/new-post-comment
          ]]
   (->> (parse-coll :notification)
        (filter (comp #{t} :notification/type))
        first))
 (gen :entity/notification)

 (:out (sh "ls" "-lh" (env/db-path)))

 )

;; Notes

;; - deleted members may have no :member/firebase-account
;; - (deleted members should be fully purged)


;; STEPS/GOALS

;; - group into systems
;; - turn parsed data into transactions for re-db
;; - validate refs (that they exist)
;; - export: re-db to datalevin
;; - pipeline: stream from live firebase/mongo db
;; - visualize: client streams/queries data (behind a security wall)
