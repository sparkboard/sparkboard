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

(defn $oid [x] (:$oid x x))

(defn timestamp-from-id
  ([] (timestamp-from-id :ts/created-at))
  ([to-k]
   (fn [m a v]
     (assoc m to-k (bson-id-timestamp ($oid v))
              a ($oid v)))))

(defn days-between
  ([dt] (days-between dt (t/local-date-time)))
  ([date-time-1 date-time-2]
   (t/as (t/duration (t/truncate-to date-time-1 :days)
                     (t/truncate-to date-time-2 :days)) :days)))

(defn date-time [inst] (t/local-date-time inst "UTC"))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; DB

(def MONGODB_URI (-> env/config :prod :mongodb/readonly-uri))

(def mongo-colls {:entity/member "users"
                  :entity/notification "notificationschemas"
                  :entity/project "projectschemas"
                  :entity/discussion "discussionschemas"
                  :entity/thread "threadschemas"})

(def firebase-colls {:entity/org "org"
                     :entity/board "settings"
                     :entity/domain "domain"
                     :entity/collection "collection"
                     :entity/grants "roles"
                     :entity/slack.user "slack-user"
                     :entity/slack.team "slack-team"
                     :entity/slack.broadcast "slack-broadcast"
                     :entity/slack.channel "slack-channel"})

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

(defn lookup-ref [k]
  (xf (fn lf [v]
        (if (or (set? v) (sequential? v))
          (map lf v)
          (when-some [v ($oid v)]
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
                                       (= k :entity/board)
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
          "textarea" :field.type/text-content
          :field.type/text-content))

(def all-field-types
  (->> (read-coll :entity/board)
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

(def domain->board (->> (read-coll :entity/board)
                        (fire-flat :board/id)
                        (group-by #(% "domain"))
                        (#(update-vals % (comp :board/id first)))))

(defn html-content [s]
  {:text-content/format :text.format/html
   :text-content/string s})

(defn md-content [s]
  {:text-content/format :text.format/markdown
   :text-content/string s})

(defn video-entry [v]
  (when-not (str/blank? v)
    (cond (re-find #"vimeo" v) {:field.video/format :video.format/vimeo-url
                                :field.video/vimeo-url v}
          (re-find #"youtube" v) {:field.video/format :video.format/youtube-url
                                  :field.video/youtube-url v}
          :else {:field.video/format :video.format/youtube-id
                 :field.video/youtube-id v})))

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
                                      :field.type/link-list {:field.link-list/items (mapv #(set/rename-keys % {:label :field.link-list/text
                                                                                                               :url :field.link-list/url}) v)}
                                      :field.type/select {:field.select/value v}
                                      :field.type/text-content (html-content v)
                                      :field.type/video (video-entry v)
                                      (throw (Exception. (str "Field type not found "
                                                              {:field/type field-type
                                                               :field.spec/id field-spec-id
                                                               })))))]
                  (-> (dissoc m k)
                      (cond-> field-value
                              (update
                               :field/_entity
                               ;; question, how to have uniqueness
                               ;; based on tuple of [field-spec/id, field/target]
                               (fnil conj [])
                               {:field/id (str target-id ":" field-spec-id)
                                :field/parent [target-k target-id]
                                :field/value [field-type field-value]
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

(def changes {:entity/board
              [::prepare (partial fire-flat :board/id)
               ::defaults {:board.registration/open? true
                           :board.member/private-messaging? true
                           :visibility/public? true
                           :i18n/default-locale "en"
                           :board/org [:org/id "base"]}
               "createdAt" (& (xf t/instant) (rename :ts/created-at))
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
               ::always (fn [m]
                          ;; remove old boards that have no members
                          (let [boards-to-delete #{
                                                   ;; no members
                                                   "-LPapiSmd7enBUMmSs32"
                                                   "-LY6PLDhWC6qnI5i76jM"
                                                   "-KPnCopeKpVUlydxO_UK"
                                                   "-LWXQl_hUdzMbJXuBf5d"
                                                   "-KgZC5QInj_0qNjEIwUP"
                                                   "-KPnDC_bNqN6VvSEzxTw"
                                                   "-L1si58K5bqDZB4ZMqCr"
                                                   "-LgRVOKbB1jjKmXJTlIy"
                                                   "-LWSi4Oi1Y43nw6eW_1T"
                                                   "-L9jn8dfKlqbgwk8m-qM"
                                                   "-Lje_6c6GHCrxoYllMGe"
                                                   "-Kq9wA3QfejnNjk8tp6K"
                                                   "-Lu7AhwVv1cxF-fHShxY"
                                                   }]
                            (cond-> m
                                    (boards-to-delete (:board/id m))
                                    (-> (assoc :ts/deleted-at deletion-time)
                                        (update :content/title #(or % "-deleted-"))))))
               "isTemplate" (rename :board/is-template?)
               ::always (fn [m]
                          (cond-> m
                                  (:board/is-template? m)
                                  (update :content/title #(or % "My Hackathon (Template)"))))
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
                                                                 (u/update-some {:field.spec/options (partial mapv #(update-keys % (fn [k]
                                                                                                                                     (case k "label" :field.spec.option/label
                                                                                                                                             "value" :field.spec.option/value
                                                                                                                                             "color" :field.spec.option/color
                                                                                                                                             "default" :field.spec.option/default?))))})
                                                                 (update :field.spec/order #(or % (swap! !orders inc)))
                                                                 (update :field.spec/type parse-field-type)
                                                                 (dissoc :field.spec/name)
                                                                 (assoc :field.spec/managed-by managed-by)))))
                                                (catch Exception e (prn a v) (throw e))))))]
                 ["groupFields" (& field-xf (rename :board.project/fields))
                  "userFields" (& field-xf (rename :board.member/fields))])

               "groupNumbers" (rename :board.project/show-numbers?)
               "projectNumbers" (rename :board.project/show-numbers?)
               "userMaxGroups" (& (xf #(Integer. %)) (rename :board.member/max-projects))
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
                                                      (u/update-some {:tag/restricted? (constantly true)})
                                                      (update :tag/label (fn [x] (or x ""))))))))
                         (rename :tag/_managed-by))
               "social" (& (xf (fn [m] (into {} (mapcat {"facebook" [[:social.sharing-button/facebook true]]
                                                         "twitter" [[:social.sharing-button/twitter true]]
                                                         "qrCode" [[:social.sharing-button/qr-code true]]
                                                         "all" [[:social.sharing-button/facebook true]
                                                                [:social.sharing-button/twitter true]
                                                                [:social.sharing-button/qr-code true]]})
                                             (keys m)))) (rename :board.project/sharing-buttons))
               "userMessages" (rename :board.member/private-messaging?)
               "groupSettings" rm
               "registrationLink" (rename :board.registration/register-at-url)
               "slack" (& (xf #(% "team-id"))
                          (lookup-ref :slack.team/id)
                          (rename :board/slack.team))
               "registrationOpen" (rename :board.registration/open?)
               "registrationCode" (& (xf (fn [code]
                                           (when-not (str/blank? code)
                                             {code {:registration-code/active? true}})))
                                     (rename :board/registration-codes))
               "webHooks" (& (xf (partial change-keys ["updateMember" (& (xf (partial hash-map :webhook/url))
                                                                         (rename :event.board/update-member))
                                                       "newMember" (& (xf (partial hash-map :webhook/url))
                                                                      (rename :event.board/new-member))]))
                             (rename :board/webhooks))
               "images" (& handle-image-urls
                           (rename :spark/image-urls))
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
               "groupMaxMembers" (& (xf #(Integer. %)) (rename :board.project/max-members))
               "headerJs" (rename :html/js)
               "projectTags" rm
               "registrationEmailBody" (rename :board.registration.invitation-email/body-text)
               "learnMoreLink" (rename :board.landing-page/learn-more-url)
               "metaDesc" (rename :html.meta/description)
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
              :entity/org [::prepare (partial fire-flat :org/id)
                           ::defaults {:visibility/public? true}
                           "allowPublicViewing" (rename :visibility/public?)
                           "images" (& handle-image-urls
                                       (rename :spark/image-urls))
                           "showOrgTab" (rename :org.board/show-org-tab?)
                           "creator" (& (lookup-ref :member/id)
                                        (rename :ts/created-by))
                           "boardTemplate" (& (lookup-ref :board/id)
                                              (rename :org.board/template-default))]
              :entity/slack.user [::prepare (partial fire-flat :slack.user/id)
                                  "account-id" (& (lookup-ref :firebase-account/id)
                                                  (rename :slack.user/firebase-account)) ;; should account-id become spark/member-id?
                                  "team-id" (& (lookup-ref :slack.team/id)
                                               (rename :slack.user/slack.team))]
              :entity/slack.team [::prepare (partial fire-flat :slack.team/id)
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
              :entity/slack.broadcast [::prepare (partial fire-flat :slack.broadcast/id)
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
              :entity/slack.channel [::prepare (partial fire-flat :slack.channel/id)
                                     "project-id" (& (lookup-ref :project/id)
                                                     (rename :slack.channel/project))
                                     "team-id" (& (lookup-ref :slack.team/id)
                                                  (rename :slack.channel/slack.team))]
              :entity/domain [::prepare (partial mapv (fn [[name target]]
                                                        (merge {:domain/name (unmunge-domain name)}
                                                               (parse-domain-target target))))]
              :entity/collection [::prepare (partial fire-flat :collection/id)
                                  "images" (& handle-image-urls
                                              (rename :spark/image-urls))
                                  "boards" (& (xf (fn [m] (into [] (comp (filter val) (map key)) m)))
                                              (lookup-ref :board/id)
                                              (rename :collection/boards)) ;; ordered list!

                                  ]
              :entity/grants [::prepare
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
              ::firebase ["localeSupport" (rename :i18n/suggested-locales)
                          "languageDefault" (rename :i18n/default-locale)
                          "socialFeed" (& (xf (partial change-keys
                                                       ["twitterHashtags" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/hashtags))
                                                        "twitterProfiles" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/profiles))
                                                        "twitterMentions" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/mentions))]))
                                          (rename :social/feed))
                          "domain" (& (lookup-ref :domain/name) (rename :http/domain))
                          "title" (rename :content/title)]

              :entity/member [::defaults {:member/new? false
                                          :member/project-participant? true
                                          :member.admin/inactive? false
                                          :member/email-frequency :member.email-frequency/periodic}
                              :lastModifiedBy (& (lookup-ref :member/id)
                                                 (rename :ts/modified-by))
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
                                                           :grant/roles (into [] (comp (map (partial keyword "role")) (distinct)) roles)}]))))
                                        (rename :grant/_member))
                              :tags (& (fn [m a v]
                                         (let [board-id (second (:member/board m))]
                                           (assoc m a (mapv (partial str board-id ":") v))))
                                       (lookup-ref :tag/id)
                                       (rename :member/tags))

                              :newsletterSubscribe (rename :member/newsletter-subscription?)
                              :active (& (xf not) (rename :member.admin/inactive?)) ;; same as deleted?
                              :picture (& (xf #(when-not (str/starts-with? % "/images/")
                                                 %))
                                          (rename :member/image-url))
                              :votesByDomain (& (fn [m a votes]
                                                  (assoc m a
                                                           (mapv (fn [[domain id]]
                                                                   (let [board-id (second (:member/board m))]
                                                                     {:member-vote.entry/id (str board-id ":" (:member/id m))
                                                                      :member-vote.entry/member [:member/id (:member/id m)]
                                                                      :member-vote.entry/board [:board/id board-id]
                                                                      :member-vote.entry/project [:project/id id]})) votes)))
                                                (rename :member-vote.entry/_member))
                              :suspectedFake (rename :member.admin/suspected-fake?)

                              :feedbackRating rm

                              ::always (fn [m]
                                         (if (or (:ts/deleted-at m)
                                                 (str/blank? (:name m)))
                                           (-> m
                                               (assoc :ts/deleted-at deletion-time
                                                      :member/name "DELETED")
                                               (update :member/firebase-account #(or % [:firebase-account/id "DELETED"]))
                                               (update :member/board #(or % [:board/id "DELETED"]))
                                               (dissoc :member/image-url :member/tags))))

                              ]
              :entity/discussion [::prepare (fn [m]
                                              (when-not (empty? (:posts m))
                                                m))
                                  :_id (& (timestamp-from-id)
                                          (rename :discussion/id))
                                  :type rm
                                  :user (& (lookup-ref :member/id)
                                           (rename :ts/created-by))
                                  :text (& (xf html-content) (rename :post/text-content))
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
                                                             :user (rename :ts/created-by)
                                                             :text (rename :post.comment/text)
                                                             :parent rm]))
                                               (rename :post/comments))
                                  :boardId (& (lookup-ref :board/id)
                                              (rename :discussion/board))]
              :entity/project [::defaults {:project.admin/inactive? false}
                               :_id (& (timestamp-from-id)
                                       (rename :project/id))
                               :field_description (& (xf html-content)
                                                     (rename :project.admin/description-content))
                               ::always (parse-fields :project/id :project/board)
                               :boardId (& (lookup-ref :board/id)
                                           (rename :project/board))
                               :lastModifiedBy (& (lookup-ref :member/id)
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
                                             (update m a (partial mapv (fn [m]
                                                                         (let [role (case (:role m)
                                                                                      "admin" :role/admin
                                                                                      "editor" :role/collaborator
                                                                                      (if (= (:ts/created-by m)
                                                                                             (:user_id m))
                                                                                        :role/admin
                                                                                        :role/member))]
                                                                           (-> m
                                                                               (dissoc :role :user_id)
                                                                               (assoc :grant/id (grant-id (:user_id m) project-id)
                                                                                      :grant/roles [role]
                                                                                      :grant/entity [:project/id project-id]
                                                                                      :grant/member [:member/id (:user_id m)]))))))))
                                         (rename :grant/_entity))
                               :looking_for (& (xf (fn [ss] (mapv (partial hash-map :request/text) ss)))
                                               (rename :project/open-requests))
                               :sticky (rename :project.admin/sticky?)
                               :demoVideo (& (xf video-entry)
                                             (rename :project/video))
                               :discussion rm ;; unused
                               ::always (fn [m]
                                          (cond-> m
                                                  (str/blank? (:content/title m))
                                                  (assoc :ts/deleted-at deletion-time :content/title "DELETED")
                                                  (:ts/deleted-at m)
                                                  (update :content/title #(or % "DELETED"))))
                               ]
              :entity/notification [::always (fn notification-filter [m]
                                               (let [m (-> m
                                                           (dissoc :targetViewed :notificationViewed)
                                                           (assoc :notification/viewed? (boolean (or (:targetViewed m)
                                                                                                     (:notificationViewed m)))))
                                                     day-threshold 180
                                                     created-at (bson-id-timestamp ($oid (:_id m)))] ;; remove notifications older than this
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
                                                                            :notification/thread [:thread/id ($oid targetId)]}
                                                              "newPost" {:notification/type :notification.type/new-discussion-post}
                                                              "newComment" {:notification/type :notification.type/new-post-comment})))))
                                    :notification/board rm

                                    ]
              :entity/thread [:_id (& (timestamp-from-id)
                                      (rename :thread/id))
                              :participantIds (& (lookup-ref :member/id)
                                                 (rename :thread/members))
                              :createdAt (& (xf parse-$date)
                                            (rename :ts/created-at))
                              :readBy (& (xf keys)
                                         (xf (partial mapv (fn [k] [:member/id (name k)])))
                                         (rename :thread/read-by)) ;; change to a set of has-unread?
                              :modifiedAt (& (xf parse-$date) (rename :ts/updated-at))

                              ;; TODO - :messages
                              :messages (& (xf (partial change-keys [:_id (& (timestamp-from-id)
                                                                             (rename :thread.message/id))
                                                                     :createdAt rm
                                                                     :body (rename :thread.message/text)
                                                                     :senderId (& (lookup-ref :member/id)
                                                                                  (rename :ts/created-by))
                                                                     :senderData rm
                                                                     ::always (fn [m]
                                                                                ;; remove empty messages
                                                                                (if (str/blank? (:thread.message/text m))
                                                                                  (reduced nil)
                                                                                  m))]))
                                           (rename :thread/messages))
                              :boardId rm #_(& (lookup-ref :board/id)
                                               (rename :thread/board))
                              ::always (fn [m]
                                         ;; remove empty threads
                                         (if (seq (:thread/messages m))
                                           m
                                           (reduced nil)))]
              ::mongo [:deleted (& (xf (constantly deletion-time))
                                   (rename :ts/deleted-at))
                       :updatedAt (& (xf parse-$date) (rename :ts/updated-at))
                       :title (rename :content/title)
                       :intro (rename :project/summary-text)
                       :owner (& (xf $oid) (lookup-ref :member/id) (rename :ts/created-by))

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

(defn explain-all! []
  (reset! sschema/!registry (merge (m/default-schemas)
                                   (update-vals sschema/extra-schema :spark/schema)))
  (->> colls
       (mapcat (fn [k]
                 (:errors (m/explain [:sequential (@sschema/!registry k)] (take 1 (parse-coll k))))))
       first))

(defn parse-all []
  (into []
        (mapcat parse-coll)
        colls))

(defn register-schema! []
  (reset! sschema/!registry (merge (m/default-schemas)
                                   (update-vals sschema/extra-schema :spark/schema))))

(comment

 (reset! sschema/!registry (merge (m/default-schemas)
                                  (update-vals sschema/extra-schema :spark/schema)))

 (fetch-mongodb)
 (fetch-firebase)
 (explain-all!)
 (:out (sh "ls" "-lh" (env/db-path)))

(gen [:map [:domain/target-type [:= "x"]]])
 (let [_ (register-schema!)
       docs (parse-all)
       reverse-ks (into #{} (comp (mapcat keys)
                                  (filter #(str/starts-with? (name %) "_"))) docs)
       reverse-docs (into [] (mapcat (fn [k] (mapcat k docs))) reverse-ks)
       docs (mapv #(apply dissoc % reverse-ks) docs)]
   (first (for [doc (into docs reverse-docs)
                :let [schema (sschema/entity-schema doc)
                      _ (when-not schema (prn :no-schema doc))
                      explained (m/explain schema doc)]
                :when explained]
            explained)))

 (map parse-domain-target (vals (read-coll :entity/domain)))

 (keep :board.registration/register-at-url (parse-coll :entity/board))

 ;; one-time handling of (1) old boards with no members, (2)
 #_(let [members-by-board (group-by (comp second :member/board) (parse-coll :entity/member))
         boards-by-id (-> (parse-coll :entity/board)
                          (->> (group-by :board/id))
                          (update-vals first))]
     (def empty-boards (set (for [board-id (keys boards-by-id)
                                  :let [board (boards-by-id board-id)]
                                  :when (and (not (:board/is-template? board))
                                             (or (str/blank? (:content/title board))
                                                 (and
                                                  (empty? (members-by-board board-id))
                                                  ;; empty boards can be removed if they're over 90 days old,
                                                  ;; or missing createdAt field (these are all old)
                                                  (or (not (:ts/created-at board))
                                                      (> (days-between (date-time (:ts/created-at board))) 90)))))]
                              board-id)))
     (def board-created-at (into {} (for [board-id (->> (vals boards-by-id)
                                                        (remove :ts/created-at)
                                                        (map :board/id))
                                          :let [created-at (->> (members-by-board board-id)
                                                                (map :ts/created-at)
                                                                sort
                                                                first)
                                                board (boards-by-id board-id)]
                                          :when created-at]
                                      [board-id created-at]))))

 (clojure.core/time (count (parse-all)))
 (first (parse-coll :entity/member))
 (->> (parse-coll :entity/member)
      (filter (comp #(some-> % (str/starts-with? "/images/"))
                    :member/image-url))
      count
      )

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
