(ns sb.migrate.one-time
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
            [sb.app.chat.data :as chat]
            [sb.schema :as sch :refer [composite-uuid]]
            [sb.server.datalevin :as sb.dl :refer [conn]]
            [sb.server.env :as env]
            [re-db.api :as db]
            [sb.util :as u]
            [sb.server.assets :as assets])
  (:import (java.lang Integer)
           (java.util Date)))

;; For exploratory or one-off migration steps.
;;
;; The `mongoexport` command is required.
;; MacOS: brew tap mongodb/brew && brew install mongodb-community
;; Alpine: apk add --update-cache mongodb-tools

(defn role-kw [entity-kind role-name]
  (case role-name
    "member" nil

    ("editor"
      "admin") (keyword "role" (str (name entity-kind) "-" role-name))

    ;; deprecating separate 'owner' role (this was only used in projects)
    "owner" (keyword "role" (str (name entity-kind) "-" "admin"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; TIME

(defn bson-id-timestamp [id]
  (new Date (* 1000 (Integer/parseInt (subs id 0 8) 16))))

(defn get-oid [x] (str (:$oid x x)))

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

(def mongo-colls {:membership/as-map   "users"
                  :account/as-map      "users"
                  :ballot/as-map       "users"
                  :notification/as-map "notificationschemas"
                  :project/as-map      "projectschemas"
                  :note/as-map         "projectschemas"
                  :discussion/as-map   "discussionschemas"
                  :chat/as-map         "threadschemas"
                  })

(def firebase-colls {:org/as-map             "org"
                     :board/as-map           "settings"
                     :collection/as-map      "collection"
                     :roles/as-map           "roles"
                     :slack.user/as-map      "slack-user"
                     :slack.team/as-map      "slack-team"
                     :slack.broadcast/as-map "slack-broadcast"
                     :slack.channel/as-map   "slack-channel"})

(def colls (vec (concat (keys mongo-colls)
                        (keys firebase-colls))))

(def !accounts
  (delay
    (->> (json/read-value
           (slurp (env/db-path "accounts.json"))
           json/keyword-keys-object-mapper)
         :users
         (remove :disabled))))

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

(defn assoc-some-value
  ([m a v]
   (if-some [v (some-value v)]
     (assoc m a v)
     (dissoc m a)))
  ([m a v & kvs]
   (reduce
     (fn [m [a v]] (assoc-some-value m a v))
     (assoc-some-value m a v)
     (partition 2 kvs))))

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
  (memoize                                                  ;; you'll need to eval & run `fetch-firebase` below for this to work
    (fn [] (->> (slurp (env/db-path "firebase.edn")) (read-string)))))

(def read-coll
  (memoize
    (fn [k]
      (cond (#{:account/as-map} k) @!accounts
            (mongo-colls k)
            (read-string (slurp (env/db-path (str (mongo-colls k) ".edn"))))
            (firebase-colls k) (->> (-> ((read-firebase) (firebase-colls k))
                                        (cond->>
                                          (= k :board/as-map) (merge-with merge ((read-firebase) "privateSettings")))
                                        #_(#(apply dissoc % delete-boards)))
                                    #_(mapv (fn [[k m]] (assoc m :firebase/id k))))
            :else (throw (ex-info (str "Unknown coll " k) {:coll k}))))))

(def !members-raw
  (delay (into []
               (remove #(or (:deleted %)
                            (:suspectedFake %)
                            (nil? (:firebaseAccount %))
                            (nil? (:boardId %))
                            (= "example" (:boardId %))))
               (read-coll :membership/as-map))))

(def !firebaseAccount->email
  (delay
    (into {}
          (map (juxt :localId :email))
          @!accounts)))

(defn firebase-account->email [account where]
  (or (@!firebaseAccount->email account)
      (throw (ex-info (str "Firebase email not found for " account) {:account account
                                                                     :where   where}))))

(def to-uuid
  (memoize
    (fn [kind s]
      (let [s (:$oid s s)]
        (cond (uuid? s) (do (assert (= kind (sch/kind s false))
                                    (str "wrong kind. expected " kind, "got " (sch/kind s) ". id: " s))
                            s)
              (and (vector? s) (= :entity/id (first s))) (do (when-not (= kind (sch/kind (second s) false))
                                                               (throw (ex-info (str "unexpected uuid kind: " kind " " s " ")
                                                                               {:expected-kind kind
                                                                                :actual-kind   (sch/kind (second s))
                                                                                :s             s
                                                                                })))
                                                             (second s))
              (string? s) (sb.dl/to-uuid kind (if (and (= kind :account)
                                                       (not (str/includes? s "@")))
                                                (firebase-account->email s 186)
                                                s))
              :else (throw (ex-info "Invalid UUID" {:s s :kind kind})))))))

(def !member-id->account-uuid
    (delay
      (into {}
            (map (fn [member]
                   [(-> member :_id get-oid)
                    (sch/to-uuid :account (-> (:firebaseAccount member)
                                              (firebase-account->email 195)))]))
            @!members-raw)))

(def !member-id->board-id
  (delay
    (into {}
          (map (fn [member]
                 [(-> member :_id get-oid)
                  (-> member :boardId)]))
          @!members-raw)))

(def !member-id->board-membership-id*
  (delay
    (into {}
          (map (fn [member]
                 [(-> member :_id get-oid)
                  (composite-uuid :membership
                                  (sch/to-uuid :account (-> (:firebaseAccount member)
                                                            (firebase-account->email 289)))
                                  (sch/to-uuid :board (:boardId member)))]))
          @!members-raw)))

(defn member->board-membership-id [member-id]
  (@!member-id->board-membership-id* (get-oid member-id)))

(defn member->account-uuid [member-id]
    ;; member-id (mongo userId) to account-uuid,
    ;; pre-filtered: returns nil for missing members
  (@!member-id->account-uuid (get-oid member-id)))

#_(defn @!member-id->board-membership-id
    (when-let [account-id (member->account-uuid member-id)]
      (let [board-id (sch/to-uuid :board board-id)]
        (composite-uuid :membership account-id board-id))))

(defn uuid-ref [kind s]
  [:entity/id (to-uuid kind s)])

(defn members->member-refs [member-ids]
  (into []
        (comp (keep member->board-membership-id)
              (map (partial uuid-ref :membership)))
        member-ids))

(defn member->account-ref [member-id]
  (sch/wrap-id (member->account-uuid member-id)))

(comment
  (coll-entities :ballot/as-map)
  *e
  (uuid-ref [:entity/id #uuid "3b7f78a0-6757-3da3-ac3d-265f7c9c3d0f"]))


(defn id-with-timestamp [kind m a v]
  (-> m
      (assoc :entity/created-at (bson-id-timestamp (get-oid v))
             :entity/id (to-uuid kind (get-oid v)))
      (dissoc a)))

(def !account->member-docs
  (delay
    (-> @!members-raw
        (->> (group-by :firebaseAccount))
        (dissoc nil)
        (update-keys (partial to-uuid :account))
        (update-vals #(->> %
                           (sort-by (comp bson-id-timestamp get-oid :_id)))))))

(defn unmunge-domain-name [s] (str/replace s "_" "."))

(declare coll-entities)

(defn uuid-ref-as [kind as]
  (fn [m k v]
    (-> m
        (dissoc k)
        (u/assoc-some as (some->> v (uuid-ref kind))))))

(def coll-entities-by-id
  (memoize
   (fn [coll-k]
     (u/index-by (coll-entities coll-k)
                 :entity/id))))

(defn missing-entity? [coll-k ref]
  (let [kind   (keyword (namespace coll-k))
        coll-k ({:post/as-map :discussion/as-map} coll-k coll-k)]
    (and ref
         (not ((coll-entities-by-id coll-k) (to-uuid kind ref))))))

(defn missing-uuid? [the-uuid]
  (let [kind   (sch/kind the-uuid)
        coll-k (keyword (name kind) "as-map")]
    (not ((coll-entities-by-id coll-k) the-uuid))))

(defn keep-entity [coll-k]
  (fn [m a v]
    (cond-> m
            (missing-entity? coll-k v)
            (dissoc a))))

;; TODO
;; look up missing entities from cached ID indexes,
;; because we have to delay this, as parsing entities
;; causes deletions...



(defn kw-keys [m]
  (walk/postwalk (fn [m]
                   (if (map? m)
                     (update-keys m keyword)
                     m)) m))

(defn flat-map [id-key id-fn m]
  (mapv (fn [[id m]] (assoc m id-key (id-fn id))) m))
;(defn fire-flat-uuids
;  [m]
;  (mapv (fn [[id m]] (assoc m :entity/id (uuid-ref id))) m))
;
;(defn fire-flat-as
;  [id-key m]
;  (mapv (fn [[id m]] (assoc m id-key id)) m))

(defn change-keys
  ([changes] (keep (partial change-keys changes)))
  ([changes doc]

   (let [changes       (->> changes flatten (keep identity) (partition 2))
         prepare       (->> changes
                            (keep (fn [[k v]] (when (= ::prepare k) v)))
                            (apply comp))
         defaults      (->> changes
                            (keep (fn [[k v]] (when (= ::defaults k) v)))
                            (apply merge))
         splice        (->> changes
                            (keep (fn [[k v]] (when (= ::splice k) v)))
                            last)
         changes       (remove (comp #{::prepare
                                       ::defaults
                                       ::splice} first) changes)
         doc           (prepare doc)
         apply-changes (fn [doc]
                         (try
                           (some-> (reduce (fn [m [a f]]
                                             (try
                                               (if (= a ::always)
                                                 (f m)
                                                 (if-some [v (some-value (m a))]
                                                   (f m a v)
                                                   (dissoc m a)))
                                               (catch Exception e
                                                 (clojure.pprint/pprint {:a   a
                                                                         :doc m})
                                                 (throw e))))
                                           doc
                                           changes)
                                   (->> (merge defaults))
                                   (cond-> splice
                                     splice))
                           (catch Exception e
                             (prn :failed-doc doc)
                             (throw e))))]
     (when doc
       (if (sequential? doc)
         (into [] (keep apply-changes) doc)
         (apply-changes doc))))))

(defn parse-sparkboard-id [s]
  (let [[_ etype id-string] (re-find #"sparkboard[._]([^:]+):(.*)" s)
        kind (keyword etype)
        kind ({:user :account} kind kind)]
    {:kind      kind
     :uuid      (to-uuid kind id-string)
     :id-string id-string
     :ref       (uuid-ref kind id-string)}))

(defn parse-domain-name-target [target]
  ;; TODO - domains that point to URLs should be "owned" by someone
  (if (str/starts-with? target "redirect:")
    {:domain-name/redirect-url (-> (subs target 9)
                                   (str/replace "%3A" ":")
                                   (str/replace "%2F" "/"))}

    (let [{:keys [kind id-string ref uuid]} (parse-sparkboard-id target)]
      (if (= [kind id-string] [:site "account"])
        {:domain-name/redirect-url "https://account.sb.com"}
        (when-not (missing-uuid? uuid)
          {:entity/_domain-name [{:entity/id uuid}]})))))

(def !entity->domain
  (delay
    (->> ((read-firebase) "domain")
         (keep (fn [[name target]]
                 (let [[kind v] (if (str/starts-with? target "redirect:")
                                  [:domain-name/redirect-url (-> (subs target 9)
                                                                 (str/replace "%3A" ":")
                                                                 (str/replace "%2F" "/"))]
                                  (let [{:keys [kind id-string ref uuid]} (parse-sparkboard-id target)]
                                    (if (= [kind id-string] [:site "account"])
                                      [:domain-name/redirect-url "https://account.sb.com"]
                                      [:entity/id uuid])))]
                   (when (= kind :entity/id)
                     [v {:entity/kind :domain-name
                         :domain-name/name (unmunge-domain-name name)}]))))
         (into {}))))

(comment
  (db/transact! (for [[entity-id entry] @!entity->domain
                      :when (db/get [:entity/id entity-id])]
                  {:entity/id          entity-id
                   :entity/domain-name entry})))

(defn lookup-domain [m]
  (assoc-some-value m :entity/domain-name (@!entity->domain (:entity/id m))))

(defn smap [m] (apply sorted-map (apply concat m)))

(defn parse-mongo-date [d]
  (some-> (:$date d d)
          inst/read-instant-timestamp
          time/instant
          Date/from))

(defn parse-image-urls [m a urls]
  (let [image-k #(case % "logo" :image/avatar
                         "logoLarge" :image/logo-large
                         "footer" :image/footer
                         "background" :image/background
                         "subHeader" :image/sub-header)]
    (reduce-kv (fn [m k url]
                 (assoc m (image-k k) (assets/link-asset url)))
               (dissoc m a)
               urls)))

(defn parse-field-type [t]
  (case t "image" :field.type/image-list
          "video" :field.type/video
          "select" :field.type/select
          "linkList" :field.type/link-list
          "textarea" :field.type/prose
          :field.type/prose))

(def !all-fields
  (delay
    (->> (coll-entities :board/as-map)
         (mapcat (juxt :entity/member-fields :entity/project-fields))
         (mapcat identity)
         (map (juxt :field/id identity))
         (into {}))))

(defn prose [s]
  (when-not (str/blank? s)
    {:prose/format (if (str/includes? s "<")
                     :prose.format/html
                     :prose.format/markdown)
     :prose/string s}))

(defn video-url [v]
  (when (and v (not (str/blank? v)))
    (cond (re-find #"vimeo" v) v
          (re-find #"youtube" v) v
          :else (str "https://www.youtube.com/watch?v=" v))))

(defn replace-empty-str [v]
  (if (empty? v)
    "EMPTY-STRING"
    v))

(defn parse-fields [managed-by-k to-k]
  (fn [m]
    (if-some [field-ks (->> (keys m)
                            (filter #(str/starts-with? (name %) "field_"))
                            seq)]
      (let [managed-by (managed-by-k m)]
        (try
          (reduce (fn [m k]
                    (let [field-id          (composite-uuid :field
                                                            (to-uuid :board managed-by)
                                                            (to-uuid :field (subs (name k) 6)))
                          v                 (m k)
                          field-type        (:field/type (@!all-fields field-id))
                          image-list-assets (when (= field-type :field.type/image-list)
                                              (mapv assets/link-asset (cond-> v (string? v) vector)))
                          ;; NOTE - we ignore fields that do not have a spec
                          entry-value       (when field-type
                                              (case field-type
                                                :field.type/image-list (when (seq image-list-assets)
                                                                         {:image-list/images (mapv #(select-keys % [:entity/id])
                                                                                                   image-list-assets)})
                                                :field.type/link-list {:link-list/links
                                                                       (mapv #(rename-keys % {:label :link/label
                                                                                              :url   :link/url}) v)}
                                                :field.type/select {:select/value (replace-empty-str v)}
                                                :field.type/prose (prose v)
                                                :field.type/video {:video/url (video-url v)}
                                                (throw (Exception. (str "Field type not found "
                                                                        {:field/type    field-type
                                                                         :field-spec/id field-id
                                                                         })))))]
                      (-> (dissoc m k)
                          (cond-> (seq image-list-assets)
                                  (update :entity/uploads (fnil into []) image-list-assets))
                          (cond-> entry-value
                                  (assoc-in [to-k field-id] entry-value)))))
                  m
                  field-ks)
          (catch Exception e
            (clojure.pprint/pprint
              {:managed-by [managed-by-k (managed-by-k m)]
               :m          m})
            (throw e))))
      m)))

(defonce !orders (atom 0))

(def fallback-board-created-at #inst"2017-06-15T00:09:17.000-00:00")
(def deletion-time #inst"2022-07-24T18:37:05.097392000-00:00")
(def deletion-user "TO-REPLACE-WITH-ID")

(defn remove-when [pred]
  (fn [m]
    (if (pred m)
      (reduced nil)
      m)))

(comment
  (first (read-coll :board/as-map))
  (->> (read-coll :board/as-map)
       (keep #(get (val %) "tags"))
       first))

(def !tag->id
  (delay (into {}
               (for [[board-id {:strs [tags]}] (read-coll :board/as-map)
                     :let [board-id (to-uuid :board board-id)
                           tags     (->> (update-vals tags #(update % "label" (fn [l]
                                                                                (or l (get % "name")))))
                                         (filter #(get (val %) "label"))
                                         (into {}))]]
                 [board-id (-> (merge (zipmap (map #(str/lower-case (get % "label")) (vals tags))
                                              (keys tags))
                                      (zipmap (map str/lower-case (keys tags))
                                              (keys tags)))
                               (update-vals #(composite-uuid :tag board-id (to-uuid :tag %))))]))))


(comment
  (first @!tag->id)
  (get-in @!tag->id [(java.util.UUID/fromString "a17f78a0-6757-3da3-ac3d-265f7c9c3d0f")]))

(defn resolve-tag [board-id tag-string]
  (if-let [tag-id (get-in @!tag->id [(sch/unwrap-id board-id) (str/lower-case tag-string)])]
    {:tag/id tag-id}
    {:tag/label tag-string}))

(defn mongo-id? [x]
  (try (do (bson-id-timestamp x) true)
       (catch Exception e false)))

(def details-field
  {:field/id (sch/uuid-from-string "details-field")
   :field/type :field.type/prose
   :field/show-on-card? false
   :field/label "Details"})

(def !board-sticky-colors
  (delay (-> (read-coll :board/as-map)
             (update-keys (partial to-uuid :board))
             (update-vals #(get % "stickyColor")))))

(def !account-email-frequency
  (delay (into {}
               (map (fn [{:keys [firebaseAccount emailFrequency]}]
                      [(to-uuid :account firebaseAccount)
                       (case emailFrequency
                         "never" :account.email-frequency/never
                         "daily" :account.email-frequency/daily
                         "periodic" :account.email-frequency/hourly ;; labeld 'Hourly' in the old UI
                         "instant" :account.email-frequency/instant
                         :account.email-frequency/hourly)]))
               @!members-raw)))

(declare coll-entities)

(defn add-kind [kind]
  #(assoc % :entity/kind kind))

(def changes {:board/as-map
              [::prepare (partial flat-map :entity/id (partial to-uuid :board))
               ::always lookup-domain
               ::defaults {:entity/admission-policy :admission-policy/open
                           :entity/public?          true
                           :entity/parent           (uuid-ref :org "base")}
               "createdAt" (& (xf #(Date. %)) (rename :entity/created-at))
               "socialFeed" (rename :entity/social-feed)
               "localeSupport" (rename :entity/locale-suggestions)
               "languageDefault" (rename :entity/locale-default)
               ::always (let [created-times (-> {"-L5DscsDGQpIBysyKoQb" #inst"2018-02-16T10:35:27.000-00:00",
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
                                                (update-keys (partial to-uuid :board)))]
                          (fn [m]
                            (update m :entity/created-at #(or %
                                                              (created-times (:entity/id m))
                                                              fallback-board-created-at))))
               "isTemplate" (rename :board/is-template?)
               "title" (rename :entity/title)
               ::always (add-kind :board)
               ::always (fn [m]
                          (update m :entity/title (fn [x]
                                                    (or x
                                                        (if
                                                          (:board/is-template? m)
                                                          "Board Template"
                                                          "Untitled Board")))))
               (let [field-xf (fn [m a v]
                                (let [managed-by          (:entity/id m)
                                      show-on-card-labels #{"A propos de moi"
                                                            "Une phrase pour me présenter"
                                                            "Who are you?"
                                                            "Présentez-vous !"
                                                            "Je me présente en une phrase"
                                                            "One sentence Intro"
                                                            "One-Sentence Intro / Vous en quelques mots"
                                                            "One-Sentence Intro"
                                                            "Introducción de una oración"
                                                            "Une phrase qui me représente"
                                                            "My intro in 50 words! "
                                                            "Introduction brève | One-Sentence Intro"
                                                            "Je me présente en une phrase / I introduce myself in one sentence "
                                                            "Phrase d'introduction"
                                                            "Who are you? One-Sentence Intro- Qui etes-vous? une petite phrase d'intro svp"
                                                            "Un mot d'introduction ?"
                                                            "Présentez-vous en une phrase"
                                                            "My intro in 50 words!"
                                                            "Stel jezelf voor in 1 zin:"
                                                            "One liner"
                                                            "Décrivez vous en une phrase"
                                                            "Décrivez-vous en une phrase"
                                                            "About Me"
                                                            "Qui suis-je ?"
                                                            "Qui suis-je ? "
                                                            "One sentence intro"}]
                                  (assoc m a
                                           (try (->> v
                                                     (flat-map :field/id
                                                               #(composite-uuid :field
                                                                                managed-by
                                                                                (to-uuid :field %)))
                                                     (sort-by #(% "order"))
                                                     (mapv (fn [m]
                                                             (-> m
                                                                 ;; fields ids prepend their manager,
                                                                 ;; because fields have been duplicated everywhere
                                                                 ;; and have the same IDs but represent different instances.
                                                                 ;; unsure: how to re-use fields when searching across boards, etc.
                                                                 (dissoc "id" "name" "order")
                                                                 (rename-keys {"type"         :field/type
                                                                               "showOnCard"   :field/show-on-card?
                                                                               "showAtCreate" :field/show-at-registration?
                                                                               "showAsFilter" :field/show-as-filter?
                                                                               "required"     :field/required?
                                                                               "hint"         :field/hint
                                                                               "label"        :field/label
                                                                               "options"      :field/options})
                                                                 (u/update-some {:field/options
                                                                                 (partial mapv
                                                                                          #(-> %
                                                                                               (dissoc "default")
                                                                                               (update-keys (fn [k]
                                                                                                              (case k "label" :field-option/label
                                                                                                                      "value" :field-option/value
                                                                                                                      "color" :field-option/color)))
                                                                                               (update :field-option/value replace-empty-str)))})
                                                                 (u/assoc-some :field/default-value (-> (m "options")
                                                                                                        (->> (filter #(get % "default")))
                                                                                                        first
                                                                                                        (get "value")
                                                                                                        (some-> replace-empty-str)))
                                                                 (update :field/type parse-field-type)
                                                                 (as-> m
                                                                       (if (show-on-card-labels (:field/label m))
                                                                         (assoc m :field/show-on-card? true)
                                                                         m))))))
                                                (catch Exception e (prn a v) (throw e))))))]
                 ["groupFields" (& field-xf (rename :entity/project-fields))
                  "userFields" (& field-xf (rename :entity/member-fields))])

               "groupNumbers" (rename :board/project-numbers?)
               "projectNumbers" (rename :board/project-numbers?)
               "userMaxGroups" (& (xf #(Integer. %)) (rename :board/max-projects-per-member))
               "stickyColor" (& (fn [m a v]
                                  (vary-meta m assoc :board/sticky-color v))
                                rm)
               "tags" (& (fn [m a v]
                           (let [board-ref (uuid-ref :board (:entity/id m))]
                             (assoc m a (->> v
                                             (sort-by first)
                                             (flat-map :tag/id #(composite-uuid :tag
                                                                                (to-uuid :board board-ref)
                                                                                (to-uuid :tag %)))
                                             (map #(-> %
                                                       (dissoc "order")
                                                       (rename-keys {"color"    :tag/color
                                                                     "name"     :tag/label
                                                                     "label"    :tag/label
                                                                     "restrict" :tag/restricted?})
                                                       (u/update-some {:tag/restricted? (constantly true)})))
                                             (filter :tag/label)
                                             vec))))
                         (rename :entity/member-tags))
               "social" (& (xf (fn [m] (into {} (mapcat {"facebook" [[:social.sharing-button/facebook true]]
                                                         "twitter"  [[:social.sharing-button/twitter true]]
                                                         "qrCode"   [[:social.sharing-button/qr-code true]]
                                                         "all"      [[:social.sharing-button/facebook true]
                                                                     [:social.sharing-button/twitter true]
                                                                     [:social.sharing-button/qr-code true]]})
                                             (keys m)))) (rename :board/project-sharing-buttons))
               "userMessages" rm
               "groupSettings" rm
               "registrationLink" (rename :board/registration-url-override)
               "slack" (& (xf
                            (fn [{:strs [team-id]}] [:slack.team/id team-id]))
                          (rename :board/slack.team))
               "registrationOpen" (& (xf (fn [open?]
                                           (if open?
                                             :admission-policy/open
                                             :admission-policy/invite-only)))
                                     (rename :entity/admission-policy))
               "registrationCode" (& (xf (fn [code]
                                           (when-not (str/blank? code)
                                             {code {:registration-code/active? true}})))
                                     (rename :board/registration-codes))
               "webHooks" (& (xf (partial change-keys ["updateMember" (& (xf (partial hash-map :webhook/url))
                                                                         (rename :event.board/update-member))
                                                       "newMember" (& (xf (partial hash-map :webhook/url))
                                                                      (rename :event.board/new-member))]))
                             (rename :webhook/subscriptions))
               "images" parse-image-urls
               "userLabel" (& (fn [m a [singular plural]]
                                (update m :board/labels merge {:label/member.one  singular
                                                               :label/member.many plural})) rm)
               "groupLabel" (& (fn [m a [singular plural]]
                                 (update m :board/labels merge {:label/project.one  singular
                                                                :label/project.many plural})) rm)
               "publicVoteMultiple" rm

               "descriptionLong" rm                         ;;  last used in 2015

               "description" (& (xf prose)
                                (rename :entity/description)) ;; if = "Description..." then it's never used
               "publicWelcome" (& (xf prose)
                                  (rename :board/home-page-message))

               "css" (rename :board/custom-css)
               "parent" (& (xf (comp :ref parse-sparkboard-id))
                           (rename :entity/parent))
               "authMethods" rm
               "allowPublicViewing" (rename :entity/public?)
               "communityVoteSingle" (rename :member-vote/open?)
               "newsletterSubscribe" (rename :board/registration-newsletter-field?)
               "groupMaxMembers" (& (xf #(Integer. %)) (rename :board/max-members-per-project))
               "headerJs" (rename :board/custom-js)
               "projectTags" rm
               "registrationEmailBody" (rename :board/invite-email-text)
               "learnMoreLink" rm ;; all three uses are dead URLs
               "metaDesc" (rename :entity/meta-description)
               "registrationMessage" (& (xf prose)
                                        (rename :board/registration-page-message))
               "defaultFilter" rm
               "defaultTag" rm
               "locales" (rename :entity/locale-dicts)
               "filterByFieldView" rm                       ;; deprecated - see :field/show-as-filter?
               "permissions" (& (xf (constantly true))
                                (rename :board/new-projects-require-approval?))
               "projectsRequireApproval" (rename :board/new-projects-require-approval?)
               "languages" (& (xf (partial mapv #(get % "code"))) (rename :entity/locale-suggestions))]
              :org/as-map             [::prepare (partial flat-map :entity/id (partial to-uuid :org))
                                       ::defaults {:entity/public? true}
                                       ::always (add-kind :org)
                                       ::always lookup-domain
                                       "languageDefault" (rename :entity/locale-default)
                                       "localeSupport" (rename :entity/locale-suggestions)
                                       "title" (rename :entity/title)
                                       "allowPublicViewing" (rename :entity/public?)
                                       "images" parse-image-urls
                                       "showOrgTab" (rename :org/show-org-tab?)
                                       "creator" rm
                                       "boardTemplate" (uuid-ref-as :board :org/default-board-template)
                                       "socialFeed" (rename :entity/social-feed)]
              :slack.user/as-map      [::prepare (partial flat-map :slack.user/id identity)
                                       "account-id" (rename :slack.user/firebase-account-id)
                                       "team-id" (& (xf (partial vector :slack.team/id))
                                                    (rename :slack.user/slack.team))]
              :slack.team/as-map      [::prepare (partial flat-map :slack.team/id identity)
                                       "board-id" (uuid-ref-as :board :slack.team/board)
                                       "team-id" (rename :slack.team/id)
                                       "team-name" (rename :slack.team/name)
                                       "invite-link" (rename :slack.team/invite-link)
                                       "bot-user-id" (rename :slack.app/bot-user-id)
                                       "bot-token" (rename :slack.app/bot-token)
                                       "custom-messages" (& (xf (fn [m] (rename-keys m {"welcome" :slack.team/custom-welcome-message}))) (rename :slack.team/custom-messages))
                                       "app" (& (xf (fn [app]
                                                      (->> app
                                                           (flat-map :slack.app/id identity)
                                                           (change-keys ["bot-user-id" (rename :slack.app/bot-user-id)
                                                                         "bot-token" (rename :slack.app/bot-token)])
                                                           first)))
                                                (rename :slack.team/slack.app))]

              ;; response => destination-thread where team-responses are collected
              ;; reply    => team-thread where the message with "Reply" button shows up
              :slack.broadcast/as-map [::prepare (partial flat-map :slack.broadcast/id identity)
                                       "replies" (& (xf (comp (partial change-keys ["message" (rename :slack.broadcast.reply/text)
                                                                                    "user-id" (& (xf (partial vector :slack.user/id))
                                                                                                 (rename :slack.broadcast.reply/slack.user))
                                                                                    "channel-id" (rename :slack.broadcast.reply/channel-id)
                                                                                    "from-channel-id" rm])
                                                              (partial filter #(seq (% "message")))
                                                              (partial flat-map :slack.broadcast.reply/id identity)))
                                                    (rename :slack.broadcast/slack.broadcast.replies))
                                       "message" (rename :slack.broadcast/text)
                                       "user-id" (& (xf (partial vector :slack.user/id)) (rename :slack.broadcast/slack.user))
                                       "team-id" (& (xf (partial vector :slack.team/id)) (rename :slack.broadcast/slack.team))
                                       "response-channel" (rename :slack.broadcast/response-channel-id)
                                       "response-thread" (rename :slack.broadcast/response-thread-id)
                                       ]
              :slack.channel/as-map   [::prepare (partial flat-map :slack.channel/id identity)
                                       "project-id" (uuid-ref-as :project :slack.channel/project)
                                       ::always (remove-when (comp (partial missing-entity? :project/as-map) :slack.channel/project))
                                       "team-id" (& (xf (partial vector :slack.team/id))
                                                    (rename :slack.channel/slack.team))]
              :collection/as-map      [::prepare (partial flat-map :entity/id (partial to-uuid :collection))
                                       ::always (add-kind :collection)
                                       "title" (rename :entity/title)
                                       "images" parse-image-urls
                                       "boards" (& (xf (fn [m] (into [] (comp (filter val) (map (comp (partial uuid-ref :board) key))) m)))
                                                   (rename :collection/boards)) ;; ordered list!

                                       ]

              :roles/as-map           [::prepare
                                       (fn [{:strs [e-u-r]}]
                                         (into [] (mapcat
                                                    (fn [[ent user-map]]
                                                      (for [[user role-map] user-map
                                                            :let [account    (parse-sparkboard-id user)
                                                                  the-entity (parse-sparkboard-id ent)
                                                                  _          (assert (= (:kind account) :account))]]
                                                        {:entity/id         (composite-uuid :membership (:uuid the-entity) (:uuid account))
                                                         :entity/kind       :membership
                                                         :membership/member (uuid-ref :account (:uuid account))
                                                         :membership/entity (:ref the-entity)
                                                         :membership/roles  (into #{}
                                                                                  (comp (filter val)
                                                                                        (map key)
                                                                                        (map (partial role-kw (:kind the-entity))))
                                                                                  role-map)})))
                                               e-u-r))]
              ::firebase              ["socialFeed" (xf (partial change-keys
                                                                 ["twitterHashtags" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/hashtags))
                                                                  "twitterProfiles" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/profiles))
                                                                  "twitterMentions" (& (xf #(into #{} (str/split % #"\s+"))) (rename :social-feed.twitter/mentions))]))
                                       "domain" rm]
              :account/as-map         [::prepare (fn [accounts]
                                                   (->> accounts
                                                        (keep (fn [{:as account [provider] :providerUserInfo}]
                                                                (let [account-id (to-uuid :account (:email account))]
                                                                  (when-let [most-recent-member-doc (last (@!account->member-docs account-id))]
                                                                    (assoc-some-value {}
                                                                                      :entity/id account-id
                                                                                      :entity/kind :account
                                                                                      :entity/created-at (-> account :createdAt Long/parseLong time/instant Date/from)
                                                                                      :image/avatar (when-let [src (or (some-> (:picture most-recent-member-doc)
                                                                                                                               (u/guard #(not (str/starts-with? % "/images"))))
                                                                                                                       (:photoUrl account)
                                                                                                                       (:photoUrl provider))]
                                                                                                      (assets/link-asset src))
                                                                                      :account/display-name (:name most-recent-member-doc)
                                                                                      :account/email (:email account)
                                                                                      :account/email-verified? (:emailVerified account)
                                                                                      :account/email-frequency (@!account-email-frequency account-id
                                                                                                                :account.email-frequency/hourly)
                                                                                      :account/last-sign-in (some-> account :lastSignedInAt Long/parseLong time/instant Date/from)
                                                                                      :account/password-hash (:passwordHash account)
                                                                                      :account/password-salt (:salt account)
                                                                                      :account.provider.google/sub (:rawId provider))))))))]
              :ballot/as-map          [::prepare (fn [users]
                                                   (->> users
                                                        (mapcat (fn [{:as ballot :keys [_id boardId votesByDomain]}]
                                                                  (try (doall (for [[domain project-id] votesByDomain
                                                                                    :let [project-id    (to-uuid :project project-id)
                                                                                          board-id      (to-uuid :board boardId)
                                                                                          account-id (member->account-uuid _id)]
                                                                                    :when account-id]
                                                                                {:entity/kind       :ballot
                                                                                 :entity/created-by (sch/wrap-id account-id)
                                                                                 :ballot/key        (str/join "+" [board-id account-id project-id])
                                                                                 :ballot/board+account (str/join "+" [board-id account-id])
                                                                                 :ballot/board      (uuid-ref :board board-id)
                                                                                 :ballot/project    (uuid-ref :project project-id)}))
                                                                       (catch Exception e
                                                                         (prn ballot)
                                                                         (prn e)
                                                                         (throw e)))))))
                                       ::always (remove-when #(or (missing-entity? :project/as-map (:ballot/project %))
                                                                  (missing-entity? :board/as-map (:ballot/board %))))]

              ;; board memberships
              :membership/as-map      [::always (remove-when #(contains? #{"example" nil} (:boardId %)))
                                       ::always (remove-when (comp nil? :firebaseAccount))
                                       ::always (remove-when :entity/deleted-at)
                                       ::always (remove-when :suspectedFake)
                                       ::always (add-kind :membership)

                                       ::defaults {:membership/inactive? false}
                                       :lastModifiedBy rm
                                       :salt rm
                                       :hash rm
                                       :passwordResetToken rm
                                       :email rm            ;; in account
                                       :account rm
                                       :_id (fn [m a v]
                                              (-> m
                                                  (assoc :entity/created-at (bson-id-timestamp (get-oid v))
                                                         :entity/id (member->board-membership-id v))
                                                  (dissoc a)))
                                       :boardId (uuid-ref-as :board :membership/entity)

                                       ::always (parse-fields :membership/entity :entity/field-entries)
                                       :account rm

                                       :firebaseAccount (uuid-ref-as :account :membership/member)

                                       :emailFrequency rm
                                       :acceptedTerms rm
                                       :contact_me rm

                                       :first_time rm
                                       :ready rm
                                       ;; new feature - not-joining-a-project


                                       :name rm
                                       :roles (& (xf #(into #{} (keep (partial role-kw :board)) %))
                                                 (rename :membership/roles))
                                       :tags (& (fn [m a v]
                                                  (let [tags (keep (partial resolve-tag (:membership/entity m)) v)
                                                        {tags        true
                                                         custom-tags false} (group-by (comp boolean :tag/id) tags)]
                                                    (-> m
                                                        (u/assoc-seq :entity/tags tags)
                                                        (u/assoc-seq :entity/custom-tags (vec custom-tags))
                                                        (dissoc :tags)))))

                                       :newsletterSubscribe (rename :membership/newsletter-subscription?)
                                       :active (& (xf not) (rename :membership/inactive?)) ;; same as deleted?
                                       :picture rm
                                       :votesByDomain rm
                                       :feedbackRating rm

                                       ]

              :discussion/as-map      [:_id rm
                                       :type rm

                                       :followers (& (xf (partial into [] (keep member->account-ref)))
                                                     (rename :post/followers))
                                       :parent (& (xf (partial to-uuid :project))
                                                  (rename :entity/id))
                                       ::always (remove-when (comp (partial missing-entity? :project/as-map) :entity/id)) ;; prune discussions from deleted projects
                                       :posts (& (xf
                                                   (partial change-keys
                                                            [:_id (partial id-with-timestamp :post)
                                                             ::always (add-kind :post)
                                                             :parent rm
                                                             :user (& (xf member->account-ref)
                                                                      (rename :entity/created-by))
                                                             ::always (remove-when (complement :entity/created-by))
                                                             ::always (remove-when (comp str/blank? :text))

                                                             :text (& (xf prose) (rename :post/text))

                                                             :doNotFollow (& (xf (partial into [] (keep member->account-ref)))
                                                                             (rename :post/do-not-follow))
                                                             :followers (& (xf (partial into [] (keep member->account-ref)))
                                                                           (rename :post/followers))
                                                             :comments (& (xf (partial change-keys
                                                                                       [:_id (partial id-with-timestamp :post)
                                                                                        ::always (add-kind :post)
                                                                                        :user (& (xf member->account-ref)
                                                                                                 (rename :entity/created-by))
                                                                                        ::always (remove-when (complement :entity/created-by))
                                                                                        ::always (remove-when (comp str/blank? :text))
                                                                                        :text (& (xf prose) (rename :post/text))
                                                                                        :parent rm]))
                                                                          (rename :post/_parent))]))
                                                 (rename :post/_parent))
                                       :boardId rm
                                       ::always (remove-when (comp empty? :post/_parent))]
              :project/as-map         [::defaults {:entity/archived?        false
                                                   :entity/admission-policy :admission-policy/open}

                                       ::always (remove-when :sticky)
                                       ::always (remove-when :entity/deleted-at)
                                       ::always (remove-when #(contains? #{"example" nil} (:boardId %)))
                                       ::always (add-kind :project)
                                       :_id (partial id-with-timestamp :project)


                                       :title (rename :entity/title)
                                       ::always (remove-when (comp str/blank? :entity/title))

                                       :field_description (& (xf prose)
                                                             (rename :project/admin-description))
                                       :boardId (uuid-ref-as :board :entity/parent)
                                       ::always (parse-fields :entity/parent :entity/field-entries)
                                       :lastModifiedBy (& (xf member->account-ref)
                                                          (rename :entity/modified-by))
                                       :tags rm             ;; no longer used - fields instead
                                       :number (rename :project/number)
                                       :badges (& (xf (partial mapv (partial hash-map :badge/label)))
                                                  (rename :project/badges)) ;; should be ref
                                       :active (& (xf not) (rename :entity/archived?))
                                       :approved (rename :project/approved?)
                                       :ready (& (xf (fn [ready?]
                                                       (if ready? :admission-policy/invite-only
                                                                  :admission-policy/open)))
                                                 (rename :entity/admission-policy))
                                       :members (&
                                                  (fn [project a v]
                                                    (let [project-id (:entity/id project)]
                                                      (assoc project a (keep
                                                                         (fn [{:as   member
                                                                               :keys [user_id]}]
                                                                           (when-let [account-id (member->account-uuid user_id)]
                                                                             (let [role (if (and (not (:role member))
                                                                                                 (sch/id= account-id (:entity/created-by project)))
                                                                                          :role/project-admin
                                                                                          (some->> (:role member) (role-kw :project)))]
                                                                               (merge {:entity/id         (composite-uuid :membership project-id account-id)
                                                                                       :entity/kind       :membership
                                                                                       :membership/member (uuid-ref :account account-id)}
                                                                                      (when role {:membership/roles #{role}})))))
                                                                         v))))
                                                  (rename :membership/_entity))

                                       ::always (remove-when #(and (not (:entity/created-by %))
                                                                   (empty? (:members/_entity %))))

                                       :looking_for (& (xf (fn [ss] (mapv (partial hash-map :request/text) ss)))
                                                       (rename :project/open-requests))
                                       :sticky rm
                                       :demoVideo (& (xf #(hash-map :video/url (video-url %)))
                                                     (rename :entity/video))
                                       :discussion rm       ;; unused
                                       ]
              :note/as-map            [::defaults {:entity/archived?        false
                                                   :entity/admission-policy :admission-policy/open}

                                       ::always (remove-when (complement :sticky))
                                       ::always (remove-when :entity/deleted-at)
                                       ::always (remove-when #(contains? #{"example" nil} (:boardId %)))
                                       ::always (add-kind :note)
                                       :_id (partial id-with-timestamp :note)


                                       :title (rename :entity/title)
                                       ::always (remove-when (comp str/blank? :entity/title))

                                       :boardId (uuid-ref-as :board :entity/parent)
                                       ::always (fn [note]
                                                  (let [board-id (sch/unwrap-id (:entity/parent note))
                                                        board ((coll-entities-by-id :board/as-map) board-id)]
                                                    ;; nb. we can't take sticky-color from `board` because it gets filtered out
                                                    (-> {:note/outline-color (@!board-sticky-colors board-id)
                                                         :entity/fields (->> (:entity/project-fields board)
                                                                             (into [] (filter (comp (:entity/field-entries note {})
                                                                                                    :field/id))))}
                                                        u/prune
                                                        (merge note))))

                                       :field_description (& (xf prose)
                                                             (fn [note _ details-field-entry]
                                                               (-> note
                                                                   (update :entity/fields (comp vec (partial cons details-field)))
                                                                   (update :entity/field-entries assoc (:field/id details-field)
                                                                           details-field-entry)))
                                                             rm)
                                       ::always (parse-fields :entity/parent :entity/field-entries)
                                       :lastModifiedBy (& (xf member->account-ref)
                                                          (rename :entity/modified-by))
                                       :tags rm             ;; no longer used - fields instead
                                       :number rm
                                       :badges (& (xf (partial mapv (partial hash-map :badge/label)))
                                                  (xf (partial change-keys
                                                               [::defaults {:badge/color "#aaaaaa"}]))
                                                  (rename :note/badges)) ;; should be ref
                                       :active (& (xf not) (rename :entity/archived?))
                                       :approved (rename :project/approved?)
                                       :ready (& (xf (fn [ready?]
                                                       (if ready?
                                                         :admission-policy/invite-only
                                                         :admission-policy/open)))
                                                 (rename :entity/admission-policy))
                                       :members (&
                                                  (fn [note a v]
                                                    (let [note-id (:entity/id note)]
                                                      (assoc note a (keep
                                                                      (fn [{:as   member
                                                                            :keys [user_id]}]
                                                                        (when-let [account-id (member->account-uuid user_id)]
                                                                          {:entity/id         (composite-uuid :membership note-id account-id)
                                                                           :entity/kind       :membership
                                                                           :membership/member (uuid-ref :account account-id)}))
                                                                     v))))
                                                  (rename :membership/_entity))

                                       ::always (remove-when #(and (not (:entity/created-by %))
                                                                   (empty? (:members/_entity %))))

                                       :looking_for rm
                                       :sticky rm
                                       :demoVideo (& (xf #(hash-map :video/url (video-url %)))
                                                     (rename :entity/video))
                                       :discussion rm       ;; unused
                                       :project/card-classes (rename :note/card-classes)
                                       ]
              :notification/as-map    [#_#_
                                       ::always (remove-when
                                                 (fn notification-filter [m]
                                                   (let [day-threshold 180]
                                                     (or (:notification/viewed? m)
                                                         (> (-> (get-oid (:_id m))
                                                                bson-id-timestamp
                                                                date-time
                                                                days-since)
                                                            day-threshold)))))
                                       ::always (add-kind :notification)
                                       :_id (partial id-with-timestamp :notification)
                                       ;; TODO is it worth to resurect this code to add created-at to project membershipts?
                                       #_#_
                                       :_id (fn [m a v]
                                              (-> m
                                                  ;; Project memberships don't have created-at so we supply it here
                                                  (cond-> (= "newMember" (:type m))
                                                    (assoc :entity/created-at (bson-id-timestamp (get-oid v))))
                                                  (dissoc a)))
                                       :createdAt rm
                                       :boardId rm
                                       :recipientId (& (xf #(some-> % member->account-ref vector))
                                                       (rename :notification/recipients))
                                       ::always (remove-when (complement :notification/recipients))
                                       :data (fn [m a v] (-> m (dissoc a) (merge v)))
                                       :project rm
                                       :user rm
                                       :message rm
                                       :discussion rm
                                       ::always (fn [{:notification/keys [project account recipients] :as m}]
                                                  (when-let [subject-id (case (:type m)
                                                                          "newMember" (when account
                                                                                        (-> (composite-uuid :membership project account)
                                                                                            ;; TODO check how many of these are note memberships
                                                                                            (u/guard (coll-entities-by-id :project/as-map))))
                                                                          ;; chat notifcations are generated by the migration of chats
                                                                          "newMessage" nil
                                                                          "newPost" (-> (:post m)
                                                                                        :id
                                                                                        (->> (to-uuid :post))
                                                                                        (u/guard (coll-entities-by-id :discussion/as-map)))
                                                                          "newComment" (-> (:comment m)
                                                                                           :id
                                                                                           (->> (to-uuid :post))
                                                                                           (u/guard (coll-entities-by-id :discussion/as-map))))]
                                                    (-> m
                                                        (dissoc :type :targetId :targetPath
                                                                :post :comment
                                                                :targetViewed :notificationViewed
                                                                :notificationEmailed)
                                                        (assoc :notification/subject [:entity/id subject-id]
                                                               ;; Because in the old data we only have one notification (with possibly multiple recipients)
                                                               ;; We can derive the notification id from the subject id and so merge all recipients into one notification entity
                                                               :entity/id (sb.dl/to-uuid :notification (str subject-id))
                                                               :notification/type (case (:type m)
                                                                                    "newMember" :notification.type/new-member
                                                                                    ("newPost" "newComment") :notification.type/new-post))
                                                        (cond-> (or (:targetViewed m)
                                                                    (:notificationViewed m))
                                                          (assoc :notification/unread-by recipients))
                                                        (cond-> (not (:notificationEmailed m))
                                                          (assoc :notification/email-to recipients)))))
                                       ::always (remove-when (comp nil? :entity/id))
                                       ::always (remove-when (comp nil? :notification/subject))]
              :chat/as-map            [::always (remove-when #(contains? #{"example" nil} (:boardId %)))
                                       :participantIds (xf #(-> (into [] (keep member->account-ref) %)
                                                                (u/guard (comp #{(count %)} count))))
                                       ::always (remove-when (complement :participantIds))
                                       :messages (xf (partial change-keys [:_id (partial id-with-timestamp :message)
                                                                           :createdAt rm
                                                                           ::always (add-kind :chat.message)
                                                                           ::always (remove-when (comp str/blank? :body))
                                                                           :body (& (xf prose)
                                                                                    (rename :chat.message/content))
                                                                           :senderId (& (xf member->account-ref)
                                                                                        (rename :entity/created-by))
                                                                           :senderData rm]))
                                       ::always (fn [{:keys [messages participantIds readBy]}]
                                                  {:messages
                                                   (-> (mapv (fn [message]
                                                               (assoc message
                                                                      :chat.message/recipient (first (remove #{(:entity/created-by message)}
                                                                                                             participantIds))))
                                                             (sort-by :entity/created-at messages))
                                                       (update (dec (count messages))
                                                               (fn [{:keys [chat.message/recipient] :as message}]
                                                                 (cond-> message
                                                                   (not ((update-keys readBy (comp member->account-uuid name))
                                                                         recipient))
                                                                   (assoc :notification/unread-by [recipient]
                                                                          :notification/email-to [recipient])))))})
                                       ::splice :messages]
              ::mongo                 [:deleted (& (xf (fn [x] (when x deletion-time)))
                                                   (rename :entity/deleted-at))
                                       ::always (remove-when :entity/deleted-at)
                                       :updatedAt (& (xf parse-mongo-date) (rename :entity/updated-at))
                                       :intro (& (xf prose)
                                                 (rename :entity/description))
                                       :owner (& (xf member->account-ref)
                                                 (rename :entity/created-by))

                                       :htmlClasses (fn [m k v]
                                                      (let [classes (str/split v #"\s+")]
                                                        (-> m
                                                            (dissoc k)
                                                            (u/assoc-some :project/card-classes (some-> (remove #{"sticky"} classes)
                                                                                                        seq
                                                                                                        distinct
                                                                                                        vec))
                                                            (cond->
                                                              (some #{"sticky"} classes)
                                                              (assoc :sticky true)))))


                                       #_#_:_id (& (xf #(:$oid % %))
                                                   (fn [m a id] (assoc m :entity/created-at (bson-id-timestamp id)))
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
          :let [_   (prn :starting coll-k)
                {:keys [out err exit]} (sh "mongoexport"    ;; <-- you'll need this installed; see https://www.mongodb.com/docs/database-tools/installation/installation/
                                           "--uri" MONGODB_URI
                                           "--jsonArray"
                                           "--collection" mongo-coll)
                _   (prn :downloaded coll-k)
                clj (->> (json/read-value out json/keyword-keys-object-mapper)
                         #_(mapv parse-mongo-doc))
                _   (prn :parsed coll-k)]]
    (when-not (zero? exit)
      (throw (Exception. err)))
    (spit (env/db-path (str mongo-coll ".edn")) clj)))



(defn fetch-accounts []
  ;; if you get a 401 despite having a valid service-account in .local.config.edn,
  ;; try `firebase logout` first
  (let [json-path (doto (env/db-path "accounts.json") (->> (sh "rm")))
        {:keys [out err exit]} (try
                                 (spit (env/db-path "service-account.json")
                                       (-> env/config :prod :firebase/service-account json/write-value-as-string))
                                 (sh "npx"
                                     "firebase"
                                     "auth:export"
                                     json-path
                                     "-P"
                                     (:project_id (:firebase/service-account (:prod env/config)))
                                     :env
                                     (into {"GOOGLE_APPLICATION_CREDENTIALS"
                                            (env/db-path "service-account.json")}
                                           (System/getenv)))
                                 (finally (sh "rm" (env/db-path "service-account.json"))))]
    (if (seq err)
      (prn :fetch-accounts/error {:err err :out out})
      (do
        (prn :downloaded-accounts out)
        (spit (env/db-path "accounts.edn")
              (json/read-value
                (slurp (env/db-path "accounts.json"))
                json/keyword-keys-object-mapper))))))

(comment
  (fetch-accounts))

(defn fetch-firebase []
  (let [{token             :firebase/database-secret
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

(defn flatten-entities-xf []
  "Walks entities to pull out nested relations (eg. where a related entity is stored 'inline')"
  (let [reverse-ks (into {} (comp (filter #(str/starts-with? (name %) "_"))
                                  (map (juxt identity #(keyword (namespace %) (subs (name %) 1)))))
                         (keys @sch/!schema))]
    (mapcat (fn [doc]
              (if (sequential? doc)
                (into []
                      (flatten-entities-xf)
                      doc)
                (let [doc-id (sch/wrap-id doc)]
                  (into [(apply dissoc doc (keys reverse-ks))]
                        (flatten-entities-xf)
                        (mapcat (fn [[reverse-k k]]
                                  (map #(assoc % k doc-id)
                                       (reverse-k doc)))
                                reverse-ks))))))))

(def coll-entities
  "Converts doc according to `changes`"
  (memoize
    (fn [k]
      (try
        (->> (read-coll k)
             (change-keys (changes-for k))
             (into [] (flatten-entities-xf)))
        (catch Exception e
          (prn :failed-in k)
          (throw e))))))

(defn explain-errors! []
  (sch/install-malli-schemas!)
  (->> colls
       (mapcat (fn [k]
                 (for [entity (coll-entities k)
                       :let [schema (@sch/!malli-registry k)
                             _      (when-not schema
                                      (prn :NO_SCHEMA))]
                       :when (not (m/validate schema entity))]
                   (do (clojure.pprint/pprint entity)
                       (sb.validate/humanized schema entity)))))
       first))

(defn read-all []
  (into []
        (mapcat read-coll)
        colls))

(defn all-entities
  "Flat list of all entities (no inline nesting)" []
  (into []
        (mapcat coll-entities)
        colls))

(defn contains-somewhere?
  "Deep walk of a data structure to see if `v` exists anywhere inside it (via =)"
  [v coll]
  (let [!found (atom false)]
    (walk/postwalk #(do (when (= v %) (reset! !found true)) %) coll)
    @!found))

(comment

  (->> (all-entities)
       (map :entity/kind)
       frequencies)

  (->> (all-entities)
       (filter (complement :entity/kind))
       (mapcat keys)
       distinct
       (map namespace)
       distinct)

  ;; TODO what about entities with :slack.*/* keys?

  (->> colls
       (mapcat read-coll)
       (filter (partial contains-somewhere? "b014b8bd-195a-3d40-8ef3-2ea7f0ad6eab"))
       )
  (sch/kind #uuid "b014b8bd-195a-3d40-8ef3-2ea7f0ad6eab")

  (do (doall (for [k colls]
               (do (prn k)
                   (coll-entities k))))
      nil)

  (first (map :chat/key (coll-entities :chat/as-map)))
  (coll? {})

  (take 2 (all-entities))


  ;; Steps to set up a Datalevin db
  (dl/clear conn)                                           ;; delete all (if exists)

  ;; XXX delete `./.db/datalevin` dir

  ;; (on my machine, the next line fails if I don't re-eval `sb.server.datalevin` here)


  ;; transact schema

  (def entities (all-entities))
  (db/merge-schema! @sch/!schema)
  ;; "upsert" lookup refs
  (db/transact! (mapcat sch/unique-keys entities))
  (db/transact! entities)

  (count (filter :entity/id (mapcat sch/unique-keys entities)))
  ;; transact lookup refs first,

  ;; ;; then transact everything else
  ;; (d/transact! (all-entities))


  ;; only for debugging when something fails to transact
  (doseq [doc (all-entities)]
    (try (db/transact! [doc])
         (catch Exception e
           (prn :fail doc)
           (throw e))))

  (explain-errors!)                                         ;; checks against schema

  ;; for investigations: look up any entity by id
  (def all-ids (->> (mapcat sch/unique-keys (all-entities))
                    (mapcat vals)
                    (into #{})))

  ;; example of looking for any project that contains a particular id
  (->> (all-entities)
       (filter (partial contains-somewhere? "X"))
       distinct)
  (parse-sparkboard-id "sparkboard_user:ORqqqQ1zcFPAtWYIB4QBWlkc4Kp1")
  (coll-entities :account/as-map)


  ;; misc
  (explain-errors!)
  (:out (sh "ls" "-lh" (env/db-path)))
  (map parse-domain-name-target (vals (read-coll :domain-name/as-map)))

  ;; try validating generated docs
  (check-docs
    (mapv mg/generate (vals @sch/!entity-schemas)))

  ;; generate examples for every schema entry
  (mapv (juxt identity mg/generate) (vals @sch/!entity-schemas))

  (clojure.core/time (count (all-entities)))
  (firebase-account->email "m_5898a0e8f869e80400b2cfef" :foo)

  #_(def missing (->> (all-entities)
                      (filter (partial contains-somewhere? #uuid "4d2b41ae-5fe2-36c9-955d-0b9b0b10770b"))
                      first))
  )

;; Notes

;; - deleted members may have no :member/account
;; - deleted members and boards should yet be fully purged
;; - use :db/isComponent for thorough deletions?


;; STEPS/GOALS

;; - group into systems
;; - turn parsed data into transactions for re-db
;; - validate refs (that they exist)
;; - export: re-db to datalevin
;; - pipeline: stream from live firebase/mongo db
;; - visualize: client streams/queries data (behind a security wall)

