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

(def colls {:member "users"
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

(defn spark-ref [m a v]
  (if (coll? v)
    (assoc m a (mapv #(vector :spark/id %) v))
    (assoc m a [:spark/id v])))

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
  (fn [m a v] (assoc m a (apply f v args))))

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

              :newsletterSubscribe  (rename :member/newsletter-subscription?)
              :active (rename :member/active?-UNSURE) ;; same as deleted?
              :picture (rename :member/photo-url)

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
                    :notification/AMBIGUOUS_DATA (fn [m a v] (-> m  (dissoc a) (merge v)))
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



(defn parse-mongo-doc [coll m]
  (let [k-changes (->> (k-changes coll) flatten (keep identity) (partition 2))
        m-changes (->> (m-changes coll) flatten (keep identity))
        doc (walk/postwalk (fn [x]
                              (if (map? x)
                                (reduce (fn [m [a f]]
                                          (let [v (m a)]
                                            (cond (= a ::always) (f m a v)
                                                  (or (nil? v) (and (coll? v) (empty? v))) (dissoc m a)
                                                  :else (f m a v))))
                                        x
                                        k-changes)
                                x)) m)]
    (reduce (fn [doc f] (f doc)) doc m-changes)))


(defn fetch-mongodb []
  (doseq [[coll-k mongo-coll] colls
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
    (-> (str db "/.json?auth=" token)
        (slurp)
        (json/read-value)
        (->> (spit (env/db-path "firebase.edn"))))))

(defn read-firebase []
  (->> (slurp (env/db-path "firebase.edn")) (read-string)))

(defn parsed-coll [k]
  (keep (partial parse-mongo-doc k) (read-coll k)))

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
 (->> (read-firebase) (reduce-kv (fn [m k v] (assoc m k (count v))) {}))



 (into {} (map (fn [k] [k (mp/provide (read-coll k))])) (keys colls))

 (mp/provide (take 100 (shuffle (parsed-coll :discussion))))
 (take 3 (shuffle (filter :discussion/posts (parsed-coll :discussion))))
 (into #{} (map :type) (parsed-coll :discussion))


 (keep (comp seq :boardMemberships) (parsed-coll :project))
 )