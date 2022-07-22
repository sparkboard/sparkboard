(ns org.sparkboard.migration.one-time
  (:require [clojure.instant :as inst]
            [clojure.java.shell :refer [sh]]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [java-time :as t]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.provider :as mp]
            [org.sparkboard.schema :as sschema :refer [gen]]
            [org.sparkboard.server.env :as env]
            [re-db.schema :as schema]
            [tools.sparkboard.util :as u])
  (:import java.lang.Integer
           (java.util Date)))

;; For exploratory or one-off migration steps.
;;
;; The `mongoexport` command is required.
;; MacOS: brew tap mongodb/brew && brew install mongodb-community
;; Alpine: apk add --update-cache mongodb-tools
;;



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TIME

(defn date-time [inst] (t/local-date-time (:$date inst inst) "UTC"))

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
        (cond-> (some-value v)
                (assoc a v)))))

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
   (mapv (fn [[id m]] (assoc m id-key id)) m))
  ([m] (fire-flat :firebase/id m)))

(defn change-keys
  ([changes] (keep (partial change-keys changes)))
  ([changes doc]
   (let [changes (->> changes flatten (keep identity) (partition 2))
         prepare (->> changes
                      (keep (fn [[k v]] (when (= ::prepare k) v)))
                      (apply comp))
         defaults (->> changes
                       (keep (fn [[k v]] (when (= ::defaults k) v)))
                       (apply merge))
         changes (remove (comp #{::prepare
                                 ::defaults} first) changes)
         doc (prepare doc)
         apply-changes (fn [doc]
                         (some->> (reduce (fn [m [a f]]
                                            (if (= a ::always)
                                              (f m)
                                              (if-some [v (some-value (m a))]
                                                (f m a v)
                                                (dissoc m a))))
                                          doc
                                          changes)
                                  (merge defaults)))]
     (when doc
       (if (sequential? doc)
         (into [] (keep apply-changes) doc)
         (apply-changes doc))))))

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

(defn parse-$date [d] (some-> (:$date d d)
                              inst/read-instant-timestamp
                              t/instant))

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
                             (set/rename-keys urls {"logo" :image/logo-url
                                                    "logoLarge" :image/logo-large-url
                                                    "footer" :image/footer-url
                                                    "background" :image/background-url
                                                    "subHeader" :image/sub-header-url}))))

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

(def domain->board (->> (read-coll :board)
                        (fire-flat :board/id)
                        (group-by #(% "domain"))
                        (#(update-vals % (comp :board/id first)))))

(defn html-content [s]
  {:content/format :html
   :content/text s})

(defn md-content [s]
  {:content/format :markdown
   :content/text s})

(defn parse-fields [target-k managed-by-k]
  (fn [m]
    (if-some [field-ks (->> (keys m)
                            (filter #(str/starts-with? (name %) "field_"))
                            seq)]
      (reduce (fn [m k]
                (let [[managed-by-type managed-by-id] (managed-by-k m)
                      field-spec-id (str managed-by-id ":" (subs (name k) 6))
                      target-id (m target-k)
                      v (m k)
                      field-type (all-field-types field-spec-id)
                      ;; NOTE - we ignore fields that do not have a spec
                      field-value (when field-type
                                    (case field-type
                                      :field.type/image {:field.image/url v}
                                      :field.type/link-list (mapv #(set/rename-keys % {:label :field.link-list/text
                                                                                       :url :field.link-list/url}) v)
                                      :field.type/select {:field.select/value v}
                                      :field.type/content (html-content v)
                                      :field.type/video (when-not (str/blank? v)
                                                          (cond (re-find #"vimeo" v) {:field.video/format :video.format/vimeo-url
                                                                                      :field.video/url v}
                                                                (re-find #"youtube" v) {:field.video/format :video.format/youtube-url
                                                                                        :field.video/url v}
                                                                :else {:field.video/format :video.format/youtube-id
                                                                       :field.video/youtube-id v}))
                                      (throw (Exception. (str "Field type not found "
                                                              {:field/type field-type
                                                               :field.spec/id field-spec-id
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
                                {:field.spec/type field-type
                                 :field/id (str target-id ":" field-spec-id)
                                 :field/parent [target-k target-id]
                                 :field/field.spec [:field.spec/id field-spec-id]}
                                ))))))
              m
              field-ks)
      m)))

(defonce !orders (atom 0))

(defn grant-id [member-id entity-id]
  (str (cond-> member-id (vector? member-id) second)
       ":"
       (cond-> entity-id (vector? entity-id) second)))


(def changes {:board
              [::prepare (partial fire-flat :board/id)
               ::defaults {:board.registration/open? true
                           :board.member/private-messaging? true
                           :spark/public? true
                           :spark.locales/default "en"}
               (let [field-xf (fn [m a v]
                                (let [managed-by [:board/id (:board/id m)]]
                                  (assoc m a
                                           (try (->> v
                                                     (fire-flat :field.spec/id)
                                                     (sort-by #(% "order"))
                                                     (mapv (fn [m]
                                                             (-> m
                                                                 ;; field.spec ids prepend their manager,
                                                                 ;; because field.specs have been duplicated everywhere
                                                                 ;; and have the same IDs but represent different instances.
                                                                 ;; unsure: how to re-use fields when searching across boards, etc.
                                                                 (update :field.spec/id (partial str (:board/id m) ":"))
                                                                 (dissoc "id")
                                                                 (set/rename-keys {"type" :field.spec/type
                                                                                   "showOnCard" :field.spec/show-on-card?
                                                                                   "showAtCreate" :field.spec/show-at-create?
                                                                                   "showAsFilter" :field.spec/show-as-filter?
                                                                                   "required" :field.spec/required?
                                                                                   "hint" :field.spec/hint
                                                                                   "label" :field.spec/label
                                                                                   "options" :field.spec/options
                                                                                   "order" :field.spec/order
                                                                                   "name" :field.spec/name})
                                                                 (u/update-some {:field.spec/type parse-field-type
                                                                                 :field.spec/options (partial mapv #(update-keys % (fn [k]
                                                                                                                                     (case k "label" :field.spec.option/label
                                                                                                                                             "value" :field.spec.option/value
                                                                                                                                             "color" :field.spec.option/color
                                                                                                                                             "default" :field.spec.option/default?))))})
                                                                 (update :field.spec/order #(or % (swap! !orders inc)))
                                                                 (dissoc :field.spec/name)
                                                                 (assoc :field.spec/managed-by managed-by)))))
                                                (catch Exception e (prn a v) (throw e))))))]
                 ["groupFields" (& field-xf (rename :board.project/fields))
                  "userFields" (& field-xf (rename :board.member/fields))])

               "groupNumbers" (rename :board.project/show-numbers?)
               "projectNumbers" (rename :board.project/show-numbers?)
               "userMaxGroups" (rename :board.member/max-projects)
               "stickyColor" (rename :board.project/sticky-border-color)
               "tags" (& (fn [m a v]
                           (assoc m a (->> v
                                           (fire-flat :tag/id)
                                           (sort-by :tag/id)
                                           (mapv #(-> %
                                                      (update :tag/id (partial str (:board/id m) ":"))
                                                      (assoc :tag/managed-by [:board/id (:board/id m)])
                                                      (dissoc "order")
                                                      (set/rename-keys {"color" :tag/background-color
                                                                        "name" :tag/label
                                                                        "label" :tag/label
                                                                        "restrict" :tag/restricted?})
                                                      (u/update-some {:tag/restricted? (constantly true)}))))))
                         (rename :tag/_managed-by))
               "social" (& (xf (fn [m] (into {} (mapcat {"facebook" [[:sharing-button/facebook true]]
                                                         "twitter" [[:sharing-button/twitter true]]
                                                         "qrCode" [[:sharing-button/qr-code true]]
                                                         "all" [[:sharing-button/facebook true]
                                                                [:sharing-button/twitter true]
                                                                [:sharing-button/qr-code true]]})
                                             (keys m)))) (rename :board.project/sharing-buttons))
               "userMessages" (rename :board.member/private-messaging?)
               "groupSettings" rm
               "registrationLink" (rename :board.registration/register-at-url)
               "slack" (& (xf #(% "team-id")) (lookup-ref :slack/team-id) (rename :board/slack.team))
               "registrationOpen" (rename :board.registration/open?)
               "images" (& handle-image-urls
                           (rename :board/image-urls))
               "userLabel" (& (fn [m a [singular plural]]
                                (update m :board/labels merge {:label/member.one singular
                                                               :label/member.many plural})) rm)
               "groupLabel" (& (fn [m a [singular plural]]
                                 (update m :board/labels merge {:label/project.one singular
                                                                :label/project.many plural})) rm)
               "createdAt" (& (xf t/instant) (rename :spark/created-at))
               "publicVoteMultiple" rm

               "descriptionLong" rm ;;  last used in 2015

               "description" (& (xf html-content)
                                (rename :board.landing-page/description-content)) ;; if = "Description..." then it's never used
               "publicWelcome" (& (xf html-content)
                                  (rename :board.landing-page/instruction-content))

               "css" (rename :html/css)
               "parent" (& (xf parse-sparkboard-id)
                           (rename :board/org))
               "authMethods" rm
               "allowPublicViewing" (rename :spark/public?)
               "communityVoteSingle" (fn [m a v]
                                       (-> m
                                           (dissoc a)
                                           (assoc :community-vote/_board [{:community-vote/board [:board/id (:board/id m)]
                                                                           :community-vote/open? v}])))
               "newsletterSubscribe" (rename :board.registration/newsletter-subscription-field?)
               "groupMaxMembers" (& (xf #(Integer. %)) (rename :board.project/max-members))
               "headerJs" (rename :html/js)
               "projectTags" rm
               "registrationEmailBody" (& (xf md-content) (rename :board.registration.invitation-email/body-text))
               "learnMoreLink" (rename :board.landing-page/learn-more-url)
               "metaDesc" (rename :html.meta/description)
               "isTemplate" (rename :board/is-template?)
               "registrationMessage" (& (xf html-content)
                                        (rename :board.registration/pre-registration-content))
               "defaultFilter" rm
               "defaultTag" rm
               "locales" (rename :spark.locales/dictionary)
               "filterByFieldView" rm ;; deprecated - see :field/show-as-filter?
               "permissions" (fn [m a v]
                               (-> m
                                   (dissoc a)
                                   (update :board/policies assoc
                                           :action/project.create {:policy/requires-role #{:role/admin}})))

               ;; TODO - add this to :board/policies, clarify difference between project.add vs project.approve
               ;; and how to specify :action/project.approval {:policy/requires-role #{:role/admin}}
               "projectsRequireApproval" (fn [m a v]
                                           (-> m
                                               (dissoc a)
                                               (update :board/policies assoc
                                                       :action/project.approve {:policy/requires-role #{:role/admin}})))
               "languages" (& (xf (partial mapv #(get % "code"))) (rename :spark.locales/suggested))]
              :org [::prepare (partial fire-flat :org/id)
                    ::defaults {:spark/public? true}
                    "allowPublicViewing" (rename :spark/public?)
                    "images" (& handle-image-urls
                                (rename :org/image-urls))
                    "showOrgTab" (rename :org.board/show-org-tab?)
                    "creator" (& (lookup-ref :member/id)
                                 (rename :spark/created-by))
                    "boardTemplate" (& (lookup-ref :board/id)
                                       (rename :org.board/template-default))]
              :slack.user [::prepare (partial fire-flat :slack.user/id)
                           "account-id" (& (lookup-ref :firebase-account/id)
                                           (rename :slack.user/firebase-account)) ;; should account-id become spark/member-id?
                           "team-id" (& (lookup-ref :slack.team/id)
                                        (rename :slack.user/slack.team))]
              :slack.team [::prepare (partial fire-flat :slack.team/id)
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
              :slack.broadcast [::prepare (partial fire-flat :slack.broadcast/id)
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
              :slack.channel [::prepare (partial fire-flat :slack.channel/id)
                              "project-id" (& (lookup-ref :project/id)
                                              (rename :slack.channel/project))
                              "team-id" (& (lookup-ref :slack.team/id)
                                           (rename :slack.channel/slack.team))]
              :domain [::prepare (partial mapv (fn [[name target]]
                                                 {:domain/name (unmunge-domain name)
                                                  :domain/entity (parse-sparkboard-id target)}))]
              :collection [::prepare (partial fire-flat :collection/id)
                           "images" (& handle-image-urls
                                       (rename :collection/image-urls))
                           "boards" (& (xf (fn [m] (into [] (comp (filter val) (map key)) m)))
                                       (lookup-ref :board/id)
                                       (rename :collection/boards)) ;; ordered list!

                           ]
              :board/private [::prepare (partial fire-flat :board/id)
                              "registrationCode" (& (xf (fn [code] (when-not (str/blank? code)
                                                                     {code {:registration-code/active? true}} #{code})))
                                                    (rename :board/registration-codes))
                              "webHooks" (rename :board/webhooks)
                              "updateMember" (& (xf (partial hash-map :webhook/url)) (rename :event.board/update-member))
                              "newMember" (& (xf (partial hash-map :webhook/url)) (rename :event.board/new-member))
                              ]
              :grants [::prepare
                       (fn [{:strs [e-u-r]}]
                         (into [] (mapcat
                                   (fn [[ent user-map]]
                                     (for [[user role-map] user-map
                                           :let [entity-ref (parse-sparkboard-id ent)
                                                 member-ref (parse-sparkboard-id user)]]
                                       {:grant/id (grant-id member-ref entity-ref)
                                        :grant/entity entity-ref
                                        :grant/member member-ref
                                        :grant/roles (into [] (comp (filter val)
                                                                    (map key)
                                                                    (map (fn [r]
                                                                           (case r "admin" :role/admin)))) role-map)})))
                               e-u-r))]
              ::firebase ["localeSupport" (rename :spark.locales/suggested)
                          "languageDefault" (rename :spark.locales/default)
                          "socialFeed" (rename :spark/social-feed)
                          "twitterHashtags" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/hashtags))
                          "twitterProfiles" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/profiles))
                          "twitterMentions" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/mentions))
                          "domain" (& (lookup-ref :domain/name) (rename :spark/domain))
                          "title" (rename :spark/title)]

              :member [::defaults {:member/new? false
                                   :member/project-participant? true
                                   :member.admin/inactive? false
                                   :spark/deleted? false}

                       :salt rm
                       :hash rm
                       :passwordResetToken rm
                       :email rm ;; in account
                       :account rm
                       :_id (& (timestamp-from-id)
                               (rename :member/id))
                       :boardId (& (lookup-ref :board/id)
                                   (rename :member/board))

                       ::always (parse-fields :member/id :member/board)
                       :account rm

                       :firebaseAccount (& (lookup-ref :firebase-account/id)
                                           (rename :member/firebase-account))

                       :emailFrequency (& (xf #(keyword (or % "periodic")))
                                          (rename :member/email-frequency))
                       :acceptedTerms rm
                       :contact_me rm

                       :first_time (rename :member/new?)
                       :ready (& (xf not) (rename :member/project-participant?))
                       ;; new feature - not-joining-a-project


                       :name (rename :member/name)
                       :roles (& (fn [m a roles]
                                   (assoc m a (when (seq roles)
                                                (let [member-ref [:member/id (:member/id m)]
                                                      entity-ref (:member/board m)]
                                                  [{:grant/id (grant-id member-ref entity-ref)
                                                    :grant/member [:member/id (:member/id m)]
                                                    :grant/entity entity-ref
                                                    :grant/roles (into [] (comp (map (partial keyword "role")) (distinct)) roles)}]))))
                                 (rename :grant/_member))
                       :tags (& (fn [m a v]
                                  (let [board-id (second (:member/board m))]
                                    (assoc m a (mapv (partial str board-id ":") v))))
                                (lookup-ref :tag/id)
                                (rename :member/tags))

                       :newsletterSubscribe (rename :member/newsletter-subscription?)
                       :active (& (xf not) (rename :member.admin/inactive?)) ;; same as deleted?
                       :picture (rename :member/image-url)
                       :votesByDomain (& (fn [m a votes]
                                           (assoc m a
                                                    (mapv (fn [[domain id]]
                                                            (let [board-id (domain->board (unmunge-domain (name domain)))]
                                                              {:community-vote.entry/member (:member/id m)
                                                               :community-vote.entry/board [:board/id board-id]
                                                               :community-vote.entry/project [:project/id id]})) votes)))
                                         (rename :community-vote.entry/_member))
                       :suspectedFake (rename :member.admin/suspected-fake?)

                       :feedbackRating rm

                       ]
              :discussion [::prepare (fn [m]
                                       (when-not (empty? (:posts m))
                                         m))
                           :_id (& (timestamp-from-id)
                                   (rename :discussion/id))
                           :type rm
                           :user (& (lookup-ref :member/id)
                                    (rename :spark/created-by))
                           :text (& (xf html-content) (rename :post/content))
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
                                                                  (rename :post.comment/id))
                                                      :user (rename :spark/created-by)
                                                      :text (rename :post.comment/text)
                                                      :parent rm]))
                                        (rename :post/comments))
                           :boardId (& (lookup-ref :board/id)
                                       (rename :discussion/board))]
              :project [::defaults {:project.admin/inactive? false}
                        :_id (& (timestamp-from-id)
                                (rename :project/id))
                        :field_description (& (xf html-content)
                                              (rename :project.admin/description-content))
                        ::always (parse-fields :project/id :project/board)
                        :boardId (& (lookup-ref :board/id)
                                    (rename :project/board))
                        :lastModifiedBy (& (lookup-ref :member/id)
                                           (rename :spark/last-modified-by))
                        :tags rm ;; no longer used - fields instead
                        :number (rename :project.admin/board.number)
                        :badges (& (xf (partial mapv (partial hash-map :badge/label)))
                                   (rename :project.admin/badges)) ;; should be ref
                        :active (& (xf not) (rename :project.admin/inactive?))
                        :approved (rename :project.admin/approved?)
                        :ready (rename :project/viable-team?)
                        :members (& (xf (partial mapv
                                                 (fn [m]
                                                   (let [role (case (:role m)
                                                                "admin" :role/admin
                                                                "editor" :role/collaborator
                                                                (if (= (:spark/created-by m)
                                                                       (:user_id m))
                                                                  :role/admin
                                                                  :role/member))]
                                                     (-> m
                                                         (dissoc :role :user_id)
                                                         (assoc :grant/id (grant-id (:user_id m) (:project/id m))
                                                                :grant/roles [role]
                                                                :grant/entity [:project/id (:project/id m)]
                                                                :grant/member [:member/id (:user_id m)]))))))
                                    (rename :grant/_entity))
                        :looking_for (& (xf (fn [ss] (mapv (partial hash-map :request/text) ss)))
                                        (rename :project/open-requests))
                        :sticky (rename :project.admin/sticky?)
                        :demoVideo (rename :project/video-url)
                        :discussion rm ;; unused
                        ]
              :notification [::always (fn notification-filter [m]
                                        (let [m (-> m
                                                    (dissoc :targetViewed :notificationViewed)
                                                    (assoc :notification/viewed? (boolean (or (:targetViewed m)
                                                                                              (:notificationViewed m)))))
                                              day-threshold 180
                                              created-at (bson-id-timestamp (:$oid (:_id m)))] ;; remove notifications older than this
                                          (if (or (:notification/viewed? m)
                                                  (> (-> created-at date-time days-between)
                                                     day-threshold))
                                            (reduced nil)
                                            m)))
                             ::defaults {:notification/emailed? false}
                             :_id (& (timestamp-from-id)
                                     (rename :notification/id))
                             :createdAt rm
                             :boardId (& (lookup-ref :board/id)
                                         (rename :notification/board))
                             :recipientId (& (lookup-ref :member/id)
                                             (rename :notification/recipient))
                             :notificationEmailed (rename :notification/emailed?)
                             :data (fn [m a v] (-> m (dissoc a) (merge v)))
                             :project (& (xf :id)
                                         (lookup-ref :project/id)
                                         (rename :notification/project))
                             :user (& (xf :id)
                                      (lookup-ref :member/id)
                                      (rename :notification/member))
                             :message (& (xf :body)
                                         (rename :notification/thread.message.text)
                                         )
                             :comment (& (xf :id)
                                         (lookup-ref :post.comment/id)
                                         (rename :notification/post.comment))
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
                                                       "newMessage" {:notification/type :notification.type/new-thread-message
                                                                     :notification/thread [:thread/id targetId]}
                                                       "newPost" {:notification/type :notification.type/new-discussion-post}
                                                       "newComment" {:notification/type :notification.type/new-post-comment})))))
                             :notification/board rm

                             ]
              :thread [:_id (& (timestamp-from-id)
                               (rename :thread/id))
                       :participantIds (& (lookup-ref :member/id)
                                          (rename :thread/members))
                       :createdAt (& (xf parse-$date)
                                     (rename :spark/created-at))
                       :readBy (& (xf keys)
                                  (xf (partial mapv (fn [k] [:member/id (name k)])))
                                  (rename :thread/read-by)) ;; change to a set of has-unread?
                       :modifiedAt (& (xf parse-$date) (rename :spark/updated-at))

                       ;; TODO - :messages
                       :messages (& (xf (partial change-keys [:_id (& (timestamp-from-id)
                                                                      (rename :thread.message/id))
                                                              :createdAt rm
                                                              :body (rename :thread.message/text)
                                                              :senderId (& (lookup-ref :member/id)
                                                                           (rename :spark/created-by))
                                                              :senderData rm]))
                                    (rename :thread/messages))
                       :boardId rm #_(& (lookup-ref :board/id)
                                        (rename :thread/board))]
              ::mongo [:$oid (fn [m a v] (reduced v))
                       :$date (fn [m a v] (reduced (parse-$date v)))
                       :deleted (rename :spark/deleted?)
                       :updatedAt (& (xf parse-$date) (rename :spark/updated-at))
                       :title (rename :spark/title)
                       :intro (rename :project/summary-text)
                       :owner (& (xf :$oid) (lookup-ref :member/id) (rename :spark/created-by))

                       :htmlClasses (fn [m k v]
                                      (let [classes (str/split v #"\s+")]
                                        (-> m
                                            (dissoc k)
                                            (u/assoc-some :html/card-classes (some-> (remove #{"sticky"} classes)
                                                                                     seq
                                                                                     distinct
                                                                                     vec))
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
                                 #_#(take 200 %)
                                 #_shuffle
                                 parse-coll))))
       collect-schemas
       (mapcat (fn [[k v]] (when-not (= \_ (first (name k))) [k {sschema/s- v}])))
       (apply sorted-map)))

(defonce inferred-schemas (delay (infer-schemas)))

(defn register-schemas! [s]
  (sschema/register! (update-vals s :spark/schema))
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
        plural-types (into #{} (map pluralize) types)]
    (->> @inferred-schemas
         (merge-with merge (zipmap (keys sschema/extra-schema) (repeat nil)))
         (add-merge {:spark/schema [:map-of
                                    [:qualified-keyword {:namespace :image}]
                                    :http/url]}
                    (comp #{"image-urls"} name))
         (add-merge (fn [k]
                      (sschema/ref :one #{(keyword (name k) "id")})) (every-pred (comp types name)
                                                                                 (comp (complement #{"entity"}) namespace)))
         (add-merge (fn [k]
                      (sschema/ref :many #{(keyword (singularize (name k)) "id")})) (comp plural-types name))
         (add-merge sschema/unique-string-id (comp #{"id"} name))
         (add-merge {:spark/schema 'string?} (comp #(str/ends-with? % "text") name))
         (add-merge {:spark/schema [:set :grant/role]} (comp #{"requires-role"} name))
         (add-merge sschema/bool (comp #{\?} last name))
         (add-merge sschema/http-url (fn [k]
                                       (or (re-find #".*\burl$" (name k))
                                           (some->> (namespace k) (re-find #".*\burl$")))))
         (add-merge sschema/html-color (comp #{"color"} name))
         (#(merge-with merge % sschema/extra-schema))

         ;; merge extra-schema from sschema
         (into {})
         (register-schemas!)
         #_(#(update-vals % :spark/schema))
         (into (sorted-map)))))


(comment
 (-> (fetch-mongodb) (clojure.core/time))
 (-> (fetch-firebase) (clojure.core/time))

 (reset! sschema/!registry (m/default-schemas))
 (def inferred-schemas (atom (infer-schemas)))
 (def computed-schemas (compute-schemas))
 (do
   (def computed-schemas (compute-schemas))
   ;; register computed-schemas

   (let [schemas {:board :entity/board
                  :collection :entity/collection
                  :discussion :entity/discussion
                  :domain :entity/domain
                  :grants :entity/grant
                  :member :entity/member
                  :notification :entity/notification
                  :org :entity/org
                  :project :entity/project
                  :slack.broadcast :entity/slack.broadcast
                  :slack.channel :entity/slack.channel
                  :slack.team :entity/slack.team
                  :slack.user :entity/slack.user
                  :thread :entity/thread}
         k :thread
         get-schema #(-> computed-schemas (get (schemas %)) :spark/schema)]
     (->> (keys schemas)
          (mapcat (fn [k]
                    (:errors (m/explain [:sequential (get-schema k)] (take 1 (parse-coll k))))))
          first)

     ))
 (@sschema/!registry :entity/grant)
 (take 1 (parse-coll :grants))



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
