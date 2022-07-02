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
            )
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

(def read-coll
  (memoize
   (fn [k]
     (read-string (slurp (env/db-path (str (name k) ".edn")))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Transformation

(def rename (fn [a] (fn [m pa v] (-> m (dissoc pa) (assoc a v)))))

(def schema {:spark/deleted? (merge schema/boolean
                                    schema/one)
             :project/members (merge schema/many
                                     schema/ref)})

(defn rm [m a v] (dissoc m a))

(defn & [f & fs]
  (if (seq fs)
    (recur (fn [m a v]
             (let [m (f m a v)
                   v (m a)]
               ((first fs) m a v)))
           (rest fs))
    f))

(defn xf [f & args]
  (fn [m a v] (if-some [v (apply f v args)]
                (assoc m a v)
                (dissoc m v))))

(defn lookup-ref [k]
  (xf (fn [v] (if (coll? v)
                (mapv #(vector k %) v)
                [k v]))))

(def spark-ref (lookup-ref :spark/id))

(defn k-changes [coll]
  [:$oid (fn [m a v] (reduced v))
   :$date (fn [m a v] (reduced (t/instant (inst/read-instant-timestamp v))))
   :deleted (rename :spark/deleted?)
   :updatedAt (rename :spark/updated-at)
   :title (rename :spark/title)
   :intro (rename :spark/summary)
   :fields (rename :spark/fields)
   :owner (rename :spark/created-by)
   :lastModifiedBy (rename :spark/last-modified-by)

   :htmlClasses (fn [m k v]
                  (let [m (dissoc m k)
                        classes (set (str/split v #"\s+"))
                        sticky? (some #{"sticky"} classes)
                        classes (remove #{"sticky"} classes)]
                    (cond-> (dissoc m k)
                            (seq classes)
                            (assoc :project/card-html-classes (str/join " " classes))
                            sticky?
                            (assoc :project/sticky? true))))

   ::always (fn always [out _ _]
              (reduce-kv (fn [m k v]
                           (cond-> m
                                   (str/starts-with? (name k) "field_")
                                   (-> (dissoc k)
                                       (update
                                        :spark/fields
                                        (fnil conj [])
                                        (assoc (if (map? v) v {:value v}) :field (subs (name k) 6))))))
                         out
                         out))

   :_id (& (fn [m a id]
             (assoc m :spark/created-at (bson-id-timestamp id)))
           (rename :spark/id))

   :spark/last-modified-by spark-ref
   :spark/created-by spark-ref
   :boardId (& spark-ref
               (fn [m k v] (assoc m k #{v}))
               (rename (keyword (name coll) "board")))

   :discussion (rename (keyword (name coll) "discussion"))

   (case coll
     :member [:salt rm
              :hash rm
              :passwordResetToken rm
              :email rm ;; in account

              :firebaseAccount (& spark-ref (rename :member/account)) ;; firebaseAccount becomes [:spark/id x]

              :emailFrequency (& (xf keyword) (rename :member/email-frequency))
              :acceptedTerms (rename :member/accepted-terms?)
              :contact_me (rename :member/contact-me?)

              :first_time (rename :member/new?)
              :ready (& (xf not) (rename :member/looking-for-project?))

              :name (rename :member/name)
              :roles (& (xf (partial mapv keyword)) (rename :member/roles))
              :tags (rename :member/tags)

              :newsletterSubscribe (rename :member/newsletter-subscription?)
              :active (rename :member/active?-UNSURE) ;; same as deleted?
              :picture (rename :member/image-url)

              :votesByDomain (& (xf (fn [m] (mapv (fn [[domain id]] {:community-vote/domain (name domain) :community-vote/project [:spark/id id]}) m)))
                                (rename :community-vote/_member))
              :suspectedFake (rename :member/suspected-fake?)

              :feedbackRating rm

              ]
     :discussion [:type rm
                  :comments (rename :post/replies)
                  :user (& spark-ref (rename :post/author))
                  :text (rename :post/text)
                  :followers (& spark-ref (rename :post/followers))
                  :doNotFollow (& spark-ref (rename :post/no-follow))
                  :parent (& spark-ref (rename :post/parent))
                  :posts (rename :discussion/posts)
                  :board/_discussion rm
                  :discussion/posts (fn [m k v]
                                      (if (seq v)
                                        m
                                        nil))]
     :project [:user_id (& spark-ref (rename :membership/member))
               :role (rename :membership/role)
               :tags (rename :project/tags) ;; should be ref
               :number (rename :project/number)
               :badges (rename :project/badges) ;; should be ref
               :active (rename :project/active?)
               :approved (rename :project/approved?)
               :ready (rename :project/ready?)
               :members (rename :project/members)
               :looking_for (rename :project/looking-for)
               :sticky (rename :project/sticky?)
               :demoVideo (rename :project/video-primary)
               ]
     :notification [:recipientId (& spark-ref (rename :notification/recipient))
                    :targetId (& spark-ref (rename :notification/target))
                    :createdAt (rename :spark/created-at)
                    :type (& (xf keyword) (rename :notification/type)) #_#{:newMember :newComment :newMessage :newPost}
                    :targetViewed (rename :notification/target-viewed?)
                    :notificationViewed (rename :notification/viewed?)
                    :notificationEmailed (rename :notification/emailed?)
                    :data (rename :notification/AMBIGUOUS_DATA)
                    :project (& (xf :id) spark-ref (rename :notification/project))
                    :user (& (xf :id) spark-ref (rename :notification/member))
                    :message (& (xf :body) (rename :notification/message))
                    :comment (& (xf :id) spark-ref (rename :notification/post-comment))
                    :post (& (xf :id) spark-ref (rename :notification/post))
                    :notification/AMBIGUOUS_DATA (fn [m a v] (-> m (dissoc a) (merge v)))
                    :targetPath rm
                    ]
     :thread [:participantIds (& spark-ref (rename :thread/participants))
              :createdAt (rename :spark/created-at)
              :readBy (rename :thread/read-by) ;; change to a set of has-unread?
              :modifiedAt (rename :spark/updated-at)

              ;; TODO - :messages
              :messages (rename :thread/messages)
              :senderId (& spark-ref (rename :message/sent-by))
              :body (rename :message/body)
              :senderData rm

              ]

     nil)

   :__v rm
   :boardIds rm
   :votes rm
   :links rm
   :boardMemberships rm
   :publicVoteCount rm
   :originalBoardId rm])

(defn m-changes [coll]
  [(case coll
     :discussion
     (fn [m] (when (seq (:discussion/posts m))
               m))
     :notification
     (fn [m]
       (let [day-threshold 180] ;; remove notifications older than this
         (when (<= (-> m :spark/created-at date-time days-between)
                   day-threshold)
           ;; when older than 60 days, remove
           m)))
     nil)

   ;; process deletions after connecting the graph
   ;; (eg. to delete discussions connected to removed projects/boards)
   #_(fn [m] (when-not (:spark/deleted? m) m))])

(defn smap [m] (apply sorted-map (apply concat m)))


(comment
 (->> (parsed-coll :notification)
      count)

 (let [docs (->> (parsed-coll :thread)
                 shuffle
                 )]
   (pprint (->> (take 2 docs)
                (map smap)))
   (mp/provide docs))

 (count (read-coll :discussion))
 (count (filter identity #_(comp seq :discussion/posts) (parsed-coll :discussion)))
 (count (parsed-coll :discussion)))

(defn change-keys [changes doc]
  (let [changes (->> changes flatten (keep identity) (partition 2))]
    (walk/postwalk (fn [x]
                     (if (map? x)
                       (reduce (fn [m [a f]]
                                 (let [v (m a)]
                                   (cond (= a ::always) (f m a v)
                                         (or (nil? v) (and (coll? v) (empty? v))) (dissoc m a)
                                         :else (f m a v)))) x changes)
                       x)) doc)))

(defn parse-mongo-doc [coll m]
  (let [m-changes (->> (m-changes coll) flatten (keep identity))
        m (change-keys (k-changes coll) m)]
    (reduce (fn [doc f] (f doc)) m m-changes)))


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

(defn kw-keys [m]
  (walk/postwalk (fn [m]
                   (if (map? m)
                     (update-keys m keyword)
                     m)) m))

(defn read-firebase []
  (->> (slurp (env/db-path "firebase.edn")) (read-string)))

(defn parsed-coll [k]
  (keep (partial parse-mongo-doc k) (read-coll k)))

(defn unnest [id-key m]
  (mapv (fn [[id m]] (assoc m id-key id)) m))

(comment
 (:out (sh "ls" "-lh" (env/db-path)))

 (->> (read-coll :project)
      first
      (parse-mongo-doc :project))

 (take 1 (parsed-coll :project))
 ;; Java.lang.Integer.parseInt(String s, int radix)
 ;; download mongodb copy
 (clojure.core/time (fetch-mongodb))

 (first (parsed-coll :project))
 ;; Elapsed time: 30082.72146 msecs

 (->> (read-mongodb) (reduce-kv (fn [m k v] (assoc m k (count v))) {}))
 ;; download firebase copy

 (clojure.core/time (fetch-firebase))
 ;; Elapsed time: 1604.601176 msecs
 (keys (read-firebase))
 (def fire (read-firebase))

 (keys fire)

 (let [fb (fire "org")
       ids {"org" :org/id
            "settings" :board/id}
       boards (->> (fire "settings")
                   (unnest :spark/id)
                   (change-keys ["groupNumbers" (rename :board/show-project-numbers?)
                                 "projectNumbers" (rename :board/show-project-numbers?)
                                 "userMaxGroups" (rename :board/member-max-projects)
                                 "stickyColor" (& (fn [m _ v] (assoc-in m [:board/design :design/sticky-color] v)) rm)
                                 "description" (rename :board/description)
                                 "descriptionLong" (rename :board/description-long) ;; ?
                                 "tags" (& (xf (comp
                                                (partial mapv #(-> %
                                                                   (update-keys (fn [k] (keyword "tag" (name k))))
                                                                   (u/update-some {:tag/restrict (partial mapv keyword)})))
                                                (partial unnest :tag/name)))
                                           (rename :board/member-tags))
                                 "social" (& (xf (fn [m] (into #{} (map keyword) (keys m)))) (rename :board/project-sharing-buttons))
                                 "userMessages" (rename :board/private-messaging?)
                                 "groupSettings" rm
                                 "registrationLink" (rename :board/registration-link-override)
                                 "slack" (& (xf #(% "team-id")) (lookup-ref :slack/team-id) (rename :board/slack-team))
                                 "registrationOpen" (rename :board/registration-open?) ;; what does this mean exactly
                                 "images" (rename :spark/image-urls)
                                 "title" (rename :spark/title)
                                 "domain" (& (lookup-ref :domain/name) (rename :board/domain)) ;; ref
                                 (let [field-xf (xf
                                                 (comp
                                                  (partial mapv (fn [m]
                                                                  (-> m
                                                                      (update-keys #(keyword "field" (name %)))
                                                                      (set/rename-keys {:field/showOnCard :field/show-on-card?
                                                                                        :field/showAtCreate :field/show-at-create?
                                                                                        :field/showAsFilter :field/show-as-filter?})
                                                                      (update :field/type keyword)
                                                                      (u/update-some {:field/options (partial mapv #(update-keys % (partial keyword "field.option")))})
                                                                      (dissoc :field/name))))
                                                  (partial unnest :field/id)))]
                                   ["groupFields" (& field-xf (rename :board/project-fields))
                                    "userFields" (& field-xf (rename :board/member-fields))])
                                 "userLabel" (& (fn [m a [singular plural]]
                                                  (update m :board/labels merge {:member/one singular
                                                                                 :member/many plural})) rm)
                                 "groupLabel" (& (fn [m a [singular plural]]
                                                   (update m :board/labels merge {:project/one singular
                                                                                  :project/many plural})) rm)
                                 "createdAt" (& (xf t/instant) (rename :spark/created-at))
                                 "publicVoteMultiple" rm
                                 "publicWelcome" (rename :board/description-public) ;; verify usage
                                 "css" (rename :board/custom-css)
                                 "projectsRequireApproval" (& (xf #(when % :any-admin)) (rename :board/approval-policy))
                                 "parent" (& (xf (fn [s] (second (str/split s #":")))) spark-ref (rename :board/org))
                                 "authMethods" rm
                                 "allowPublicViewing" (rename :board/public?)
                                 "communityVoteSingle" (rename :board/community-vote-open?)
                                 "languages" (& (xf (partial mapv #(get % "code"))) (rename :board/lacales-supported))
                                 "localeSupport" (rename :board/lacales-supported)
                                 "languageDefault" (rename :board/lacale-default)
                                 "newsletterSubscribe" (rename :board/member-newsletter-checkbox?)
                                 "groupMaxMembers" (rename :board/project-max-members)
                                 "socialFeed" (rename :board/social-feed)
                                 "headerJs" (rename :board/custom-js)
                                 "projectTags" (& (xf (partial hash-map :tag/name))
                                                  (rename :board/project-tags))
                                 "registrationEmailBody" (rename :board/invitation-email-body)
                                 "learnMoreLink" (rename :board/learn-more-link)
                                 "metaDesc" (rename :board/custom-meta-description)
                                 "isTemplate" (rename :board/is-template?)
                                 "registrationMessage" (rename :board/registration-page-message)
                                 "defaultFilter" rm
                                 "defaultTag" rm
                                 "locales" (rename :board/locale-overrides)
                                 "filterByFieldView" rm ;; deprecated - see :field/show-as-filter?
                                 "permissions" (& (xf (constantly {:permission/add-project #{:admin}}))
                                                  (rename :board/permissions))
                                 ])

                   )]
   (mp/provide boards)
   #_(into #{} (map (comp :board/project-filters ))
           boards)
   )
 (mp/provide)


 (->> (read-firebase) (reduce-kv (fn [m k v] (assoc m k (count v))) {}))



 (into {} (map (fn [k] [k (mp/provide (read-coll k))])) (keys mongo-colls))

 (mp/provide (take 100 (shuffle (parsed-coll :discussion))))
 (take 3 (shuffle (filter :discussion/posts (parsed-coll :discussion))))
 (into #{} (map :type) (parsed-coll :discussion))


 (keep (comp seq :boardMemberships) (parsed-coll :project))
 )