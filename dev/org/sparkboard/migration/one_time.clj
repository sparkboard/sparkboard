(ns org.sparkboard.migration.one-time
  (:require [clojure.instant :as inst]
            [clojure.java.shell :refer [sh]]
            [clojure.set :refer [rename-keys]]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [datalevin.core :as dl]
            [java-time :as time]
            [jsonista.core :as json]
            [malli.core :as m]
            [malli.generator :as mg]
            [org.sparkboard.datalevin :as sb.dl :refer [conn]]
            [org.sparkboard.schema :as sschema]
            [org.sparkboard.server.env :as env]
            [re-db.api :as d]
            [re-db.triplestore :as ts]
            [tools.sparkboard.util :as u])
  (:import (java.lang Integer)
           (java.util Date)))

;; For exploratory or one-off migration steps.
;;
;; The `mongoexport` command is required.
;; MacOS: brew tap mongodb/brew && brew install mongodb-community
;; Alpine: apk add --update-cache mongodb-tools

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TIME

(defn bson-id-timestamp [id]
  (new Date (* 1000 (Integer/parseInt (subs id 0 8) 16))))

(defn get-oid [x] (:$oid x x))

(defn timestamp-from-id
  ([] (timestamp-from-id :ts/created-at))
  ([to-k]
   (fn [m a v]
     (assoc m to-k (bson-id-timestamp (get-oid v))
              a (get-oid v)))))

(defn days-between
  [date-time-1 date-time-2]
  (-> (time/duration (time/truncate-to date-time-1 :days)
                     (time/truncate-to date-time-2 :days))
      (time/as :days)))

(defn days-since [dt]
  (days-between dt (time/local-date-time)))

(defn date-time [inst] (time/local-date-time inst "UTC"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DB

(def MONGODB_URI (-> env/config :prod :mongodb/readonly-uri))

(def mongo-colls {:sb/member "users"
                  :sb/firebase-account "users"
                  :sb/member-vote.entry "users"
                  :sb/notification "notificationschemas"
                  :sb/project "projectschemas"
                  :sb/discussion "discussionschemas"
                  :sb/thread "threadschemas"})

(def firebase-colls {:sb/org "org"
                     :sb/board "settings"
                     :sb/domain "domain"
                     :sb/collection "collection"
                     :sb/grant "roles"
                     :sb/slack.user "slack-user"
                     :sb/slack.team "slack-team"
                     :sb/slack.broadcast "slack-broadcast"
                     :sb/slack.channel "slack-channel"})

(def colls (vec (concat (keys mongo-colls)
                        (keys firebase-colls))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Data Transformation

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

(def read-firebase
  (memoize ;; you'll need to eval & run `fetch-firebase` below for this to work
   (fn [] (->> (slurp (env/db-path "firebase.edn")) (read-string)))))

(def read-coll
  (memoize
   (fn [k]
     (let [k ({:sb/firebase-account :sb/member} k k)]
       (cond (mongo-colls k)
             (read-string (slurp (env/db-path (str (mongo-colls k) ".edn"))))
             (firebase-colls k) (-> ((read-firebase) (firebase-colls k))
                                    (cond->>
                                     (= k :sb/board) (merge-with merge ((read-firebase) "privateSettings")))
                                    #_(#(apply dissoc % delete-boards)))
             :else (throw (ex-info (str "Unknown coll " k) {:coll k})))))))

(defn unmunge-domain [s] (str/replace s "_" "."))

(declare coll-entities)

(def !id-index
  (delay
   (merge {:domain/name (delay
                         (into #{} (map :domain/name) (coll-entities :sb/domain)))
           :post/id (delay
                     (into #{} (comp (mapcat :discussion/posts)
                                     (map :post/id))
                           (coll-entities :sb/discussion)))
           :post.comment/id (delay
                             (into #{}
                                   (comp (mapcat :discussion/posts)
                                         (mapcat :post/comments)
                                         (map :post.comment/id))
                                   (coll-entities :sb/discussion)))}
          (into {} (for [k colls]
                     (let [entity-k k
                           id-k (keyword (name k) "id")]
                       [id-k
                        (delay
                         (into #{} (map id-k) (coll-entities entity-k)))]))))))

(defn ref-exists?
  ([[k id]] (ref-exists? k id))
  ([k id]
   (if-let [!ids (@!id-index k)]
     (contains? @!ids id)
     (do
       (when-not (= k :tag/id)
         (prn :index-not-found k id))
       true))))

(defn lookup-ref
  ([k id]
   (if (ref-exists? k id)
     [k id]
     [:MISSING_REF]))
  ([k]
   (xf (fn lf [v]
         (if (or (set? v) (sequential? v))
           (vec (keep lf v))
           (when-some [v (get-oid v)]
             (lookup-ref k v)))))))

(def missing-ref? #{[:MISSING_REF]})

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

(defn parse-sparkboard-id [s]
  (let [[_ etype eid] (re-find #"sparkboard[._]([^:]+):(.*)" s)]
    [(keyword ({"user" "member"} etype etype) "id") eid]))

(defn parse-domain-target [s]
  (if (str/starts-with? s "redirect:")
    {:domain/target-type :domain/url
     :domain/url (-> (subs s 9)
                     (str/replace "%3A" ":")
                     (str/replace "%2F" "/"))}
    (let [ref (parse-sparkboard-id s)]
      (if (= ref [:site/id "account"])
        {:domain/target-type :domain/url
         :domain/url "https://account.sparkboard.com"}
        {:domain/target-type :domain/entity
         :domain/entity (parse-sparkboard-id s)}))))

(comment
 (map parse-sparkboard-id ["sparkboard.org:x" "sparkboard_board:-abc"]))

(defn smap [m] (apply sorted-map (apply concat m)))

(defn parse-mongo-date [d]
  (some-> (:$date d d)
          inst/read-instant-timestamp
          time/instant
          Date/from))

(def parse-image-urls
  (xf (fn [urls]
        (rename-keys urls {"logo" :image/logo-url
                           "logoLarge" :image/logo-large-url
                           "footer" :image/footer-url
                           "background" :image/background-url
                           "subHeader" :image/sub-header-url}))))

(defn parse-field-type [t]
  (case t "image" :field.type/image
          "video" :field.type/video
          "select" :field.type/select
          "linkList" :field.type/link-list
          "textarea" :field.type/text-content
          :field.type/text-content))

(def all-field-types
  (->> (read-coll :sb/board)
       fire-flat
       (mapcat (juxt #(% "groupFields") #(% "userFields")))
       (mapcat identity)
       (map (fn [[id {:as f :strs [type]}]] [id (parse-field-type type)]))
       (into {"problem" :field.type/text-content
              "solution" :field.type/text-content
              "intro" :field.type/text-content
              "about_me" :field.type/text-content

              ;; documented ignored fields
              "description" nil ;; admin-set description is handled in parse step
              "about" nil
              "contact" nil ;; remove old 'contact' fields which often contained an email address or phone number
              "badges" nil
              "role" nil
              "department" nil
              })))

(def domain->board (->> (read-coll :sb/board)
                        (fire-flat :board/id)
                        (group-by #(% "domain"))
                        (#(update-vals % (comp :board/id first)))))

(defn html-content [s]
  (when-not (str/blank? s)
    {:text-content/format :text.format/html
     :text-content/string s}))

(defn md-content [s]
  (when-not (str/blank? s)
    {:text-content/format :text.format/markdown
     :text-content/string s}))

(defn video-value [v]
  (when-not (str/blank? v)
    (cond (re-find #"vimeo" v) [:field.video/vimeo-url v]
          (re-find #"youtube" v) [:field.video/youtube-url v]
          :else [:field.video/youtube-id v])))

(defn parse-fields [target-k managed-by-k to-k]
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
                                      :field.type/link-list {:field.link-list/items (mapv #(rename-keys % {:label :field.link-list/text
                                                                                                           :url :field.link-list/url}) v)}
                                      :field.type/select {:field.select/value v}
                                      :field.type/text-content (html-content v)
                                      :field.type/video {:field.video/value (video-value v)}
                                      (throw (Exception. (str "Field type not found "
                                                              {:field/type field-type
                                                               :field.spec/id field-spec-id
                                                               })))))]
                  (-> (dissoc m k)
                      (cond-> field-value
                              (update
                               to-k
                               ;; question, how to have uniqueness
                               ;; based on tuple of [field-spec/id, field/target]
                               (fnil conj [])
                               {:field/id (str target-id ":" field-spec-id)
                                :field/value (assoc field-value :field/type field-type)
                                :field/field.spec [:field.spec/id field-spec-id]})))))
              m
              field-ks)
      m)))

(defonce !orders (atom 0))

(defn grant-id [member-id entity-id]
  (str (cond-> member-id (vector? member-id) second)
       ":"
       (cond-> entity-id (vector? entity-id) second)))

(def fallback-board-created-at #inst"2017-06-15T00:09:17.000-00:00")
(def deletion-time #inst"2022-07-24T18:37:05.097392000-00:00")
(def deletion-user "TO-REPLACE-WITH-ID")

(defn remove-when [pred]
  (fn [m]
    (if (pred m)
      (reduced nil)
      m)))

(first (read-coll :sb/board))

(def !tag->id
  (delay (into {}
               (for [[board-id {:strs [tags]}] (read-coll :sb/board)
                     :let [tags (update-vals tags #(update % "label" (fn [l] (or l (get % "name") :NO_TAG))))]]
                 [board-id (-> (merge (zipmap (map #(str/lower-case (get % "label")) (vals tags))
                                              (keys tags))
                                      (zipmap (map str/lower-case (keys tags))
                                              (keys tags)))
                               (update-vals (partial str board-id ":")))]))))

(defn resolve-tag [board-id tag-string]
  (if-let [tag-id (get-in @!tag->id [board-id (str/lower-case tag-string)])]
    (when-not (= :NO_TAG tag-id)
      {:tag/id tag-id})
    {:tag.ad-hoc/label tag-string}))

(def remove-missing-ref (xf #(u/guard % (complement missing-ref?))))

(defn mongo-id? [x]
  (try (do (bson-id-timestamp "ORqqqQ1zcFPAtWYIB4QBWlkc4Kp1") true)
       (catch Exception e false)))

(def changes {:sb/board
              [::prepare (partial fire-flat :board/id)
               ::defaults {:board.registration/open? true
                           :member.settings/private-threads? true
                           :visibility/public? true
                           :i18n/default-locale "en"
                           :board/org [:org/id "base"]}
               "createdAt" (& (xf #(Date. %)) (rename :ts/created-at))
               ::always (fn [m]
                          (update m :ts/created-at #(or %
                                                        ({"-L5DscsDGQpIBysyKoQb" #inst"2018-02-16T10:35:27.000-00:00",
                                                          "-L2gd7dOblF0GrZ2Okez" #inst"2018-01-13T02:18:21.000-00:00",
                                                          "-KmT1SnGHaSW9J4TphXo" #inst"2017-06-13T05:05:38.000-00:00",
                                                          "-KPnCopeKpVUlydxO_UK" #inst"2016-08-22T23:16:35.000-00:00",
                                                          "-KwGOaQ0X49wFd7L-8Wo" #inst"2017-10-12T16:27:28.000-00:00",
                                                          "-L2vWuXQv-fidNtwcNf9" #inst"2018-01-16T10:12:38.000-00:00",
                                                          "-KqjE9KGCpF5eH8H1-09" #inst"2017-08-05T07:43:13.000-00:00",
                                                          "-L3jGJjZE1EOsQDiJB-z" #inst"2018-02-07T11:41:38.000-00:00",
                                                          "-Klo-nbDoocsSoJexTwE" #inst"2017-06-04T19:20:54.000-00:00",
                                                          "-KrB-vpI5B6cnz1ZSVtW" #inst"2017-08-16T07:29:56.000-00:00",
                                                          "-KPnDC_bNqN6VvSEzxTw" #inst"2016-08-23T00:41:54.000-00:00",
                                                          "-Kj9NR4mm-lHBd6O9Ec6" #inst"2017-05-02T22:36:58.000-00:00",
                                                          "-L45zh6A-WR0fkTj7nIA" #inst"2018-02-06T21:02:25.000-00:00",
                                                          "-KvlfkAntpdx0DNtMFhv" #inst"2017-10-06T12:38:28.000-00:00",
                                                          "-KpRORRAlE8UEZP1Iu0x" #inst"2017-07-19T20:09:47.000-00:00",
                                                          "-KmT2MkXxDSJuSdWG3-h" #inst"2017-06-13T10:50:38.000-00:00",
                                                          "-Kmd3PhP_Ih602zdc_AA" #inst"2017-06-15T00:09:17.000-00:00",
                                                          "-Ko4Ec8_inoz6T34_H_B" #inst"2017-09-01T13:51:51.000-00:00",
                                                          "-Kx812HJrVCfN1CTDzQf" #inst"2017-10-23T11:49:29.000-00:00",
                                                          "-KrlKwKx7d4GPhKLrpdc" #inst"2017-08-17T19:01:47.000-00:00",
                                                          "-Kyrem0Z-VDYyYn5m7I8" #inst"2017-11-15T12:54:01.000-00:00",
                                                          "-KptLeYkGMx6INLcHwzG" #inst"2017-07-25T12:48:47.000-00:00",
                                                          "-KzsRsHIopxwuAlttgaY" #inst"2017-11-28T19:12:22.000-00:00",
                                                          "-Kmd2M66G5kP6aIzpQ7e" #inst"2017-06-15T07:15:38.000-00:00"}
                                                         (:board/id m))
                                                        fallback-board-created-at)))
               "isTemplate" (rename :board/is-template?)
               "title" (rename :board/title)
               ::always (fn [m]
                          (update m :board/title (fn [x]
                                                   (or x
                                                       (if
                                                        (:board/is-template? m)
                                                         "Board Template"
                                                         "Untitled Board")))))
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
                                                                 (rename-keys {"type" :field/type
                                                                               "showOnCard" :field.spec/show-on-card?
                                                                               "showAtCreate" :field.spec/show-at-create?
                                                                               "showAsFilter" :field.spec/show-as-filter?
                                                                               "required" :field.spec/required?
                                                                               "hint" :field.spec/hint
                                                                               "label" :field.spec/label
                                                                               "options" :field.spec/options
                                                                               "order" :field.spec/order
                                                                               "name" :field.spec/name})
                                                                 (u/update-some {:field.spec/options (partial mapv #(update-keys % (fn [k]
                                                                                                                                     (case k "label" :field.spec.option/label
                                                                                                                                             "value" :field.spec.option/value
                                                                                                                                             "color" :field.spec.option/color
                                                                                                                                             "default" :field.spec.option/default?))))})
                                                                 (update :field.spec/order #(or % (swap! !orders inc)))
                                                                 (update :field/type parse-field-type)
                                                                 (dissoc :field.spec/name)
                                                                 (assoc :field.spec/managed-by managed-by)))))
                                                (catch Exception e (prn a v) (throw e))))))]
                 ["groupFields" (& field-xf (rename :project.settings/field-specs))
                  "userFields" (& field-xf (rename :board.member/fields))])

               "groupNumbers" (rename :project.settings/show-numbers?)
               "projectNumbers" (rename :project.settings/show-numbers?)
               "userMaxGroups" (& (xf #(Integer. %)) (rename :member.settings/max-projects))
               "stickyColor" (rename :board/sticky-border-color)
               "tags" (& (fn [m a v]
                           (assoc m a (->> v
                                           (fire-flat :tag/id)
                                           (sort-by :tag/id)
                                           (map #(-> %
                                                     (update :tag/id (partial str (:board/id m) ":"))
                                                     (assoc :tag/managed-by [:board/id (:board/id m)])
                                                     (dissoc "order")
                                                     (rename-keys {"color" :tag/background-color
                                                                   "name" :tag/label
                                                                   "label" :tag/label
                                                                   "restrict" :tag/restricted?})
                                                     (u/update-some {:tag/restricted? (constantly true)})))
                                           (filter :tag/label)
                                           vec)))
                         (rename :member.settings/tags))
               "social" (& (xf (fn [m] (into {} (mapcat {"facebook" [[:social.sharing-button/facebook true]]
                                                         "twitter" [[:social.sharing-button/twitter true]]
                                                         "qrCode" [[:social.sharing-button/qr-code true]]
                                                         "all" [[:social.sharing-button/facebook true]
                                                                [:social.sharing-button/twitter true]
                                                                [:social.sharing-button/qr-code true]]})
                                             (keys m)))) (rename :project.settings/sharing-buttons))
               "userMessages" (rename :member.settings/private-threads?)
               "groupSettings" rm
               "registrationLink" (rename :board.registration/register-at-url)
               "slack" (& (xf
                           (fn [{:strs [team-id]}] [:slack.team/id team-id]))
                          (rename :board/slack.team))
               "registrationOpen" (rename :board.registration/open?)
               "registrationCode" (& (xf (fn [code]
                                           (when-not (str/blank? code)
                                             {code {:registration-code/active? true}})))
                                     (rename :board.registration/codes))
               "webHooks" (& (xf (partial change-keys ["updateMember" (& (xf (partial hash-map :webhook/url))
                                                                         (rename :event.board/update-member))
                                                       "newMember" (& (xf (partial hash-map :webhook/url))
                                                                      (rename :event.board/new-member))]))
                             (rename :webhook/subscriptions))
               "images" (& parse-image-urls
                           (rename :map/image-urls))
               "userLabel" (& (fn [m a [singular plural]]
                                (update m :board/labels merge {:label/member.one singular
                                                               :label/member.many plural})) rm)
               "groupLabel" (& (fn [m a [singular plural]]
                                 (update m :board/labels merge {:label/project.one singular
                                                                :label/project.many plural})) rm)
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
               "allowPublicViewing" (rename :visibility/public?)
               "communityVoteSingle" (rename :board.member-vote/open?)
               "newsletterSubscribe" (rename :board.registration/newsletter-subscription-field?)
               "groupMaxMembers" (& (xf #(Integer. %)) (rename :project.settings/max-members))
               "headerJs" (rename :html/js)
               "projectTags" rm
               "registrationEmailBody" (rename :board.registration.invitation-email/body-text)
               "learnMoreLink" (rename :board.landing-page/learn-more-url)
               "metaDesc" (rename :html/meta-description)
               "registrationMessage" (& (xf html-content)
                                        (rename :board.registration/pre-registration-content))
               "defaultFilter" rm
               "defaultTag" rm
               "locales" (rename :i18n/extra-translations)
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
               "languages" (& (xf (partial mapv #(get % "code"))) (rename :i18n/suggested-locales))]
              :sb/org [::prepare (partial fire-flat :org/id)
                       ::defaults {:visibility/public? true}
                       "title" (rename :org/title)
                       "allowPublicViewing" (rename :visibility/public?)
                       "images" (& parse-image-urls
                                   (rename :map/image-urls))
                       "showOrgTab" (rename :board.settings/show-org-tab?)
                       "creator" (& (lookup-ref :firebase-account/id)
                                    (rename :ts/created-by))
                       "boardTemplate" (& (lookup-ref :board/id)
                                          (rename :board.settings/default-template))]
              :sb/slack.user [::prepare (partial fire-flat :slack.user/id)
                              "account-id" (rename :slack.user/firebase-account-id)
                              "team-id" (& (lookup-ref :slack.team/id)
                                           (rename :slack.user/slack.team))]
              :sb/slack.team [::prepare (partial fire-flat :slack.team/id)
                              "board-id" (& (lookup-ref :board/id)
                                            (rename :slack.team/board))
                              "team-id" (rename :slack.team/id)
                              "team-name" (rename :slack.team/name)
                              "invite-link" (rename :slack.team/invite-link)
                              "bot-user-id" (rename :slack.app/bot-user-id)
                              "bot-token" (rename :slack.app/bot-token)
                              "custom-messages" (& (xf (fn [m] (rename-keys m {"welcome" :slack.team.custom-message/welcome}))) (rename :slack.team/custom-messages))
                              "app" (& (xf (fn [app]
                                             (->> app (fire-flat :slack.app/id)
                                                  (change-keys ["bot-user-id" (rename :slack.app/bot-user-id)
                                                                "bot-token" (rename :slack.app/bot-token)])
                                                  first)))
                                       (rename :slack.team/slack.app))]

              ;; response => destination-thread where team-responses are collected
              ;; reply    => team-thread where the message with "Reply" button shows up
              :sb/slack.broadcast [::prepare (partial fire-flat :slack.broadcast/id)
                                   "replies" (& (xf (comp (partial change-keys ["message" (rename :slack.broadcast.reply/text)
                                                                                "user-id" (& (lookup-ref :slack.user/id)
                                                                                             (rename :slack.broadcast.reply/slack.user))
                                                                                "channel-id" (rename :slack.broadcast.reply/channel-id)
                                                                                "from-channel-id" rm])
                                                          (partial filter #(seq (% "message")))
                                                          (partial fire-flat :slack.broadcast.reply/id)))
                                                (rename :slack.broadcast/slack.broadcast.replies))
                                   "message" (rename :slack.broadcast/text)
                                   "user-id" (& (lookup-ref :slack.user/id) (rename :slack.broadcast/slack.user))
                                   "team-id" (& (lookup-ref :slack.team/id) (rename :slack.broadcast/slack.team))
                                   "response-channel" (rename :slack.broadcast/response-channel-id)
                                   "response-thread" (rename :slack.broadcast/response-thread-id)
                                   ]
              :sb/slack.channel [::prepare (partial fire-flat :slack.channel/id)
                                 "project-id" (& (lookup-ref :project/id)
                                                 (rename :slack.channel/project))
                                 ::always (remove-when (comp missing-ref? :slack.channel/project))
                                 "team-id" (& (lookup-ref :slack.team/id)
                                              (rename :slack.channel/slack.team))]
              :sb/domain [::prepare (partial mapv (fn [[name target]]
                                                    (merge {:domain/name (unmunge-domain name)}
                                                           (parse-domain-target target))))]
              :sb/collection [::prepare (partial fire-flat :collection/id)
                              "title" (rename :collection/title)
                              "images" (& parse-image-urls
                                          (rename :map/image-urls))
                              "boards" (& (xf (fn [m] (into [] (comp (filter val) (map key)) m)))
                                          (lookup-ref :board/id)
                                          (rename :collection/boards)) ;; ordered list!

                              ]
              :sb/grant [::prepare
                         (fn [{:strs [e-u-r]}]
                           (into [] (mapcat
                                     (fn [[ent user-map]]
                                       (for [[user role-map] user-map
                                             :let [entity-ref (parse-sparkboard-id ent)
                                                   [_ user-id :as user-ref] (parse-sparkboard-id user)]]
                                         (merge (if (mongo-id? user-id)
                                                  {:grant/member [:member/id user-id]}
                                                  {:grant/firebase-account [:firebase-account/id user-id]})
                                                {:grant/id (grant-id user-ref entity-ref)
                                                 :grant/entity entity-ref
                                                 :grant/roles (into #{} (comp (filter val)
                                                                              (map key)
                                                                              (map (fn [r]
                                                                                     (case r "admin" :role/admin)))) role-map)}))))
                                 e-u-r))]
              ::firebase ["localeSupport" (rename :i18n/suggested-locales)
                          "languageDefault" (rename :i18n/default-locale)
                          "socialFeed" (& (xf (partial change-keys
                                                ["twitterHashtags" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/hashtags))
                                                 "twitterProfiles" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/profiles))
                                                 "twitterMentions" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/mentions))]))
                                          (rename :social/feed))
                          "domain" (& (lookup-ref :domain/name)
                                      (rename :entity/domain))]
              :sb/firebase-account [::prepare (fn [users]
                                                (-> (group-by :firebaseAccount users)
                                                    (dissoc nil)
                                                    (update-vals (fn [memberships]
                                                                   (->> memberships
                                                                        (sort-by (comp bson-id-timestamp get-oid :_id))
                                                                        (map #(select-keys % [:email :firebaseAccount]))
                                                                        (apply merge))))
                                                    vals))
                                    :firebaseAccount (rename :firebase-account/id)
                                    :email (rename :firebase-account/email)]
              :sb/member-vote.entry [::prepare (fn [users]
                                                 (->> users
                                                      (mapcat (fn [{:keys [_id boardId votesByDomain]}]
                                                                (let [member-id (get-oid _id)]
                                                                  (for [[domain project-id] votesByDomain]
                                                                    {:member-vote.entry/id (str boardId ":" member-id)
                                                                     :member-vote.entry/member (lookup-ref :member/id member-id)
                                                                     :member-vote.entry/board (lookup-ref :board/id boardId)
                                                                     :member-vote.entry/project (lookup-ref :project/id project-id)}))))))
                                     ::always (remove-when #(some missing-ref? ((juxt :member-vote.entry/project
                                                                                      :member-vote.entry/member
                                                                                      :member-vote.entry/board)
                                                                                %)))]
              :sb/member [::always (remove-when #(contains? #{"example" nil} (:boardId %)))
                          ::always (remove-when (comp nil? :firebaseAccount))
                          ::always (remove-when :ts/deleted-at)

                          ::defaults {:member/new? false
                                      :member/project-participant? true
                                      :member.admin/inactive? false
                                      :member/email-frequency :member.email-frequency/periodic}
                          :lastModifiedBy rm
                          :salt rm
                          :hash rm
                          :passwordResetToken rm
                          :email rm ;; in account
                          :account rm
                          :_id (& (timestamp-from-id)
                                  (rename :member/id))
                          :boardId (& (lookup-ref :board/id)
                                      (rename :member/board))

                          ::always (parse-fields :member/id :member/board :member/fields)
                          :account rm

                          :firebaseAccount (& (lookup-ref :firebase-account/id)
                                              (rename :member/firebase-account))

                          :emailFrequency (& (xf #(case %
                                                    "never" :member.email-frequency/never
                                                    "daily" :member.email-frequency/daily
                                                    "periodic" :member.email-frequency/periodic
                                                    "instant" :member.email-frequency/instant
                                                    :member.email-frequency/periodic))
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
                                                       :grant/member member-ref
                                                       :grant/entity entity-ref
                                                       :grant/roles (into #{} (comp (map (partial keyword "role")) (distinct)) roles)}]))))
                                    (rename :grant/_member))
                          :tags (& (fn [m a v]
                                     (let [tags (keep (partial resolve-tag (second (:member/board m))) v)
                                           {tags true
                                            custom-tags false} (group-by (comp boolean :tag/id) tags)]
                                       (-> m
                                           (u/assoc-seq :member/tags (mapv :tag/id tags))
                                           (u/assoc-seq :member/tags.custom (vec custom-tags))
                                           (dissoc :tags)))))

                          :member/tags (lookup-ref :tag/id)

                          :newsletterSubscribe (rename :member/newsletter-subscription?)
                          :active (& (xf not) (rename :member.admin/inactive?)) ;; same as deleted?
                          :picture (& (xf #(when-not (str/starts-with? % "/images/")
                                             %))
                                      (rename :member/image-url))
                          :votesByDomain rm
                          :feedbackRating rm

                          ::always (remove-when (comp str/blank? :member/name))
                          ::always (remove-when :suspectedFake)]

              :sb/discussion [#_#_::prepare (fn [m]
                                              (when-not (empty? (:posts m))
                                                m))
                              :_id (& (timestamp-from-id)
                                      (rename :discussion/id))
                              :type rm
                              :followers (& (lookup-ref :member/id)
                                            (xf (partial remove missing-ref?))
                                            (rename :discussion/followers))
                              :parent (& (lookup-ref :project/id)
                                         (rename :discussion/project))
                              ::always (remove-when (comp missing-ref? :discussion/project)) ;; prune discussions from deleted projects
                              :posts (& (xf
                                         (partial change-keys
                                           [:_id (& (timestamp-from-id)
                                                    (rename :post/id))
                                            :parent rm #_(& (lookup-ref :discussion/id)
                                                            (rename :post/discussion))
                                            :user (& (lookup-ref :member/id)
                                                     (rename :ts/created-by))
                                            ::always (remove-when (comp missing-ref? :ts/created-by))
                                            ::always (remove-when (comp str/blank? :text))

                                            :text (& (xf html-content) (rename :post/text-content))

                                            :doNotFollow (& (lookup-ref :member/id)
                                                            (xf (partial remove missing-ref?))
                                                            (rename :post/do-not-follow))
                                            :followers (& (lookup-ref :member/id)
                                                          (xf (partial remove missing-ref?))
                                                          (rename :post/followers))
                                            :comments (& (xf (partial change-keys
                                                               [:_id (& (timestamp-from-id)
                                                                        (rename :post.comment/id))
                                                                :user (& (lookup-ref :member/id)
                                                                         (rename :ts/created-by))
                                                                ::always (remove-when (comp missing-ref? :ts/created-by))
                                                                :text (rename :post.comment/text)
                                                                ::always (remove-when (comp str/blank? :post.comment/text))
                                                                :parent rm]))
                                                         (rename :post/comments))]))
                                        (rename :discussion/posts))
                              :boardId rm
                              ::always (remove-when (comp empty? :discussion/posts))]
              :sb/project [::defaults {:project.admin/inactive? false}
                           :_id (& (timestamp-from-id)
                                   (rename :project/id))
                           :field_description (& (xf html-content)
                                                 (rename :project.admin/description-content))
                           ::always (parse-fields :project/id :project/board :project/fields)
                           :boardId (& (lookup-ref :board/id)
                                       (rename :project/board))
                           :lastModifiedBy (& (lookup-ref :member/id)
                                              remove-missing-ref
                                              (rename :ts/modified-by))
                           :tags rm ;; no longer used - fields instead
                           :number (rename :project.admin/board.number)
                           :badges (& (xf (partial mapv (partial hash-map :badge/label)))
                                      (rename :project.admin/badges)) ;; should be ref
                           :active (& (xf not) (rename :project.admin/inactive?))
                           :approved (rename :project.admin/approved?)
                           :ready (rename :project/viable-team?)
                           :members (&
                                     (fn [m a v]
                                       (let [project-id (:project/id m)]
                                         (update m a (partial keep (fn [m]
                                                                     (when (ref-exists? :member/id (:user_id m))
                                                                       (let [role (case (:role m)
                                                                                    "admin" :role/admin
                                                                                    "editor" :role/collaborator
                                                                                    (if (= (second (:ts/created-by m))
                                                                                           (:user_id m))
                                                                                      :role/admin
                                                                                      :role/member))]
                                                                         (-> m
                                                                             (dissoc :role :user_id)
                                                                             (assoc :grant/id (grant-id (:user_id m) project-id)
                                                                                    :grant/roles #{role}
                                                                                    :grant/entity [:project/id project-id]
                                                                                    :grant/member [:member/id (:user_id m)])))))))))
                                     (rename :grant/_entity))
                           :looking_for (& (xf (fn [ss] (mapv (partial hash-map :request/text) ss)))
                                           (rename :project/open-requests))
                           :sticky (rename :project.admin/sticky?)
                           :demoVideo (& (xf video-value)
                                         (rename :project/video))
                           :discussion rm ;; unused
                           :title (rename :project/title)

                           ::always (remove-when #(contains? #{"example" nil} (second (:project/board %))))
                           ::always (remove-when :ts/deleted-at)
                           ::always (remove-when (comp str/blank? :project/title))
                           :ts/created-by remove-missing-ref
                           ]
              :sb/notification [::defaults {:notification/emailed? false}
                                ::always (fn [m]
                                           (-> m
                                               (dissoc :targetViewed :notificationViewed)
                                               (assoc :notification/viewed? (boolean (or (:targetViewed m)
                                                                                         (:notificationViewed m))))))
                                :_id (& (timestamp-from-id)
                                        (rename :notification/id))
                                ::always (remove-when
                                          (fn notification-filter [m]
                                            (let [day-threshold 180]
                                              (or (:notification/viewed? m)
                                                  (> (-> (:ts/created-at m) date-time days-since)
                                                     day-threshold)))))

                                :createdAt rm
                                :boardId (& (lookup-ref :board/id)
                                            (rename :notification/board))
                                :recipientId (& (lookup-ref :member/id)
                                                (rename :notification/recipient))
                                ::always (remove-when (comp missing-ref? :notification/recipient))
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
                                ::always (remove-when #(or (missing-ref? (:notification/project %))
                                                           (missing-ref? (:notification/member %))
                                                           (missing-ref? (:notification/post %))
                                                           (missing-ref? (:notification/discussion %))))
                                ::always (fn [m]
                                           (let [{:keys [type targetId]} m]
                                             (-> m
                                                 (dissoc :type :targetId :targetPath)
                                                 (merge (case type
                                                          "newMember" {:notification/type :notification.type/new-project-member}
                                                          "newMessage" {:notification/type :notification.type/new-thread-message
                                                                        :notification/thread [:thread/id (get-oid targetId)]}
                                                          "newPost" {:notification/type :notification.type/new-discussion-post}
                                                          "newComment" {:notification/type :notification.type/new-post-comment})))))
                                :notification/board rm

                                ]
              :sb/thread [:_id (& (timestamp-from-id)
                                  (rename :thread/id))
                          :participantIds (& (lookup-ref :member/id)
                                             (rename :thread/members))
                          ::always (remove-when #(some missing-ref? (:thread/members %)))
                          :createdAt (& (xf parse-mongo-date)
                                        (rename :ts/created-at))
                          :readBy (& (xf keys)
                                     (xf (partial mapv (fn [k] [:member/id (name k)])))
                                     (rename :thread/read-by)) ;; change to a set of has-unread?
                          :modifiedAt (& (xf parse-mongo-date) (rename :ts/updated-at))

                          ;; TODO - :messages
                          :messages (& (xf (partial change-keys [:_id (& (timestamp-from-id)
                                                                         (rename :thread.message/id))
                                                                 :createdAt rm
                                                                 :body (rename :thread.message/text)
                                                                 :senderId (& (lookup-ref :member/id)
                                                                              (rename :ts/created-by))
                                                                 :senderData rm
                                                                 ::always (remove-when (comp str/blank?
                                                                                             :thread.message/text))]))
                                       (rename :thread/messages))
                          :boardId rm #_(& (lookup-ref :board/id)
                                           (rename :thread/board))
                          ::always (remove-when (comp empty? :thread/messages))]
              ::mongo [:deleted (& (xf (fn [x] (when x deletion-time)))
                                   (rename :ts/deleted-at))
                       ::always (remove-when :ts/deleted-at)
                       :updatedAt (& (xf parse-mongo-date) (rename :ts/updated-at))
                       :intro (rename :project/summary-text)
                       :owner (& (lookup-ref :member/id)
                                 (rename :ts/created-by))

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
                                   (fn [m a id] (assoc m :ts/created-at (bson-id-timestamp id)))
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
                {:keys [out err exit]} (sh "mongoexport" ;; <-- you'll need this installed; see https://www.mongodb.com/docs/database-tools/installation/installation/
                                           "--uri" MONGODB_URI
                                           "--jsonArray"
                                           "--collection" mongo-coll)
                _ (prn :downloaded coll-k)
                clj (->> (json/read-value out json/keyword-keys-object-mapper)
                         #_(mapv parse-mongo-doc))
                _ (prn :parsed coll-k)]]
    (when-not (zero? exit)
      (throw (Exception. err)))
    (spit (env/db-path (str mongo-coll ".edn")) clj)))

(defn fetch-firebase []
  (let [{token :firebase/database-secret
         {db :databaseURL} :firebase/app-config} (:prod env/config)]
    (->> (str db "/.json?auth=" token)
         (slurp)
         (json/read-value)
         (spit (env/db-path "firebase.edn")))))

(comment
 (fetch-firebase)
 )

(defn changes-for [k]
  (into (changes (cond (mongo-colls k) ::mongo
                       (firebase-colls k) ::firebase
                       :else (throw (ex-info (str "Unknown coll: " k) {:coll k}))))
        (changes k)))

(def coll-entities
  "Converts doc according to `changes`"
  (memoize
   (fn [k]
     (->> (read-coll k)
          (change-keys (changes-for k))))))

(defn register-schema! []
  (reset! sschema/!registry (merge (m/default-schemas)
                                   (update-vals sschema/sb-schema :malli/schema))))

(defn explain-errors! []
  (register-schema!)
  (->> colls
       (mapcat (fn [k]
                 (let [entities (coll-entities k)]
                   (:errors (m/explain [:sequential (@sschema/!registry k)] entities)))))
       first))

(defn read-all []
  (into []
        (mapcat read-coll)
        colls))

(defn root-entities
  "Entities representing docs from the original coll. May contain nested (inline) entities."
  []
  (into []
        (mapcat coll-entities)
        colls))

(def reverse-ks (into #{} (filter #(str/starts-with? (name %) "_")) (keys sschema/sb-schema)))

(defn flatten-entities
  "Walks entities to pull out nested relations (eg. where a related entity is stored 'inline')"
  [docs]
  (into []
        (mapcat (fn [doc]
                  (cons (apply dissoc doc reverse-ks)
                        (mapcat doc reverse-ks))))
        docs))

(def all-entities
  "Flat list of all entities (no inline nesting)"
  (comp flatten-entities root-entities))

(defn contains-somewhere?
  "Deep walk of a data structure to see if `v` exists anywhere inside it (via =)"
  [v coll]
  (let [!found (atom false)]
    (walk/postwalk #(do (when (= v %) (reset! !found true)) %) coll)
    @!found))

(comment

 ;; Steps to copy data from prod without processing
 (fetch-mongodb) ;; copies to ./.db
 (fetch-firebase) ;; copies to ./.db


 ;; Steps to set up a Datalevin db
 (dl/clear conn) ;; delete all (if exists)

 ;; (on my machine, the next line fails if I don't re-eval `org.sparkboard.datalevin` here)

 (do
   (d/merge-schema! sschema/sb-schema) ;; transact schema

   ;; "upsert" lookup refs
   (d/transact! (mapcat sschema/unique-keys txs))

   ;; transact the rest
   (d/transact! (all-entities)))

 ;; transact lookup refs first,

 ;; then transact everything else
 (d/transact! (all-entities))


 ;; only for debugging when something fails to transact
 (doseq [doc (all-entities)]
   (try (d/transact! [doc])
        (catch Exception e
          (prn :fail doc)
          (throw e))))

 (explain-errors!) ;; checks against schema

 ;; for investigations: look up any entity by id
 (def all-ids (->> (mapcat sschema/unique-keys (all-entities))
                   (mapcat vals)
                   (into #{})))

 ;; example of looking for any project that contains a particular id
 (->> (coll-entities :sb/project)
      (filter (partial contains-somewhere? "527a76956fe44c0200000005"))
      distinct)


 ;; misc
 (explain-errors!)
 (:out (sh "ls" "-lh" (env/db-path)))
 (map parse-domain-target (vals (read-coll :sb/domain)))

 ;; try validating generated docs
 (check-docs
  (mapv mg/generate (vals sschema/entity-schemas)))

 ;; generate examples for every schema entry
 (mapv (juxt identity mg/generate) (vals sschema/entity-schemas))

 (clojure.core/time (count (root-entities)))

 )

;; Notes

;; - deleted members may have no :member/firebase-account
;; - deleted members and boards should yet be fully purged
;; - use :db/isComponent for thorough deletions?


;; STEPS/GOALS

;; - group into systems
;; - turn parsed data into transactions for re-db
;; - validate refs (that they exist)
;; - export: re-db to datalevin
;; - pipeline: stream from live firebase/mongo db
;; - visualize: client streams/queries data (behind a security wall)
