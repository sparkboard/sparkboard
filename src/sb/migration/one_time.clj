(ns sb.migration.one-time
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
            [sb.schema :as sch]
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

(defn role-kw [role-name]
  (case role-name
    "editor" :role/editor
    "admin" :role/admin
    "owner" :role/admin
    "member" nil))

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

(def mongo-colls {:member/as-map       "users"
                  :account/as-map      "users"
                  :ballot/as-map       "users"
                  :notification/as-map "notificationschemas"
                  :project/as-map      "projectschemas"
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
               (read-coll :member/as-map))))

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
        (cond (uuid? s) (do (assert (= kind (sch/kind s))
                                    (str "wrong kind. expected " kind, "got " (sch/kind s) ". id: " s))
                            s)
              (and (vector? s) (= :entity/id (first s))) (do (when-not (= kind (sch/kind (second s)))
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
                  (to-uuid :account (:firebaseAccount member))]))
          @!members-raw)))

(def !member-id->board-id
  (delay
    (into {}
          (map (fn [member]
                 [(-> member :_id get-oid)
                  (-> member :boardId)]))
          @!members-raw)))

(defn member->account-uuid [member-id]
  ;; member-id (mongo userId) to account-uuid,
  ;; pre-filtered: returns nil for missing members
  (if (sequential? member-id)
    (into (empty member-id)
          (keep #(-> % get-oid (@!member-id->account-uuid)))
          member-id)
    (@!member-id->account-uuid (get-oid member-id))))

(defn composite-uuid [kind & ss]
  (to-uuid kind (->> ss
                     (map #(do (assert (uuid? %)) %))
                     (map str)
                     sort
                     (str/join ":"))))

(defn uuid-ref [kind s]
  (let [id (to-uuid kind s)]

    [:entity/id id]))

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
        (u/assoc-some as
                      (if (sequential? v)
                        (into []
                              (comp (keep identity)
                                    (map (partial uuid-ref kind)))
                              v)
                        (some->> v (uuid-ref kind)))))))

(def unique-ids-from
  (memoize
    (fn [coll-k]
      (into #{} (comp (mapcat sch/unique-keys)
                      (map (comp val first))) (coll-entities coll-k)))))

(defn missing-entity? [coll-k ref]
  (let [kind   (keyword (namespace coll-k))
        coll-k ({:post/as-map :discussion/as-map} coll-k coll-k)]
    (and ref
         (not ((unique-ids-from coll-k) (to-uuid kind ref))))))

(defn missing-uuid? [the-uuid]
  (let [kind   (sch/kind the-uuid)
        coll-k (keyword (name kind) "as-map")]
    (not (contains? (unique-ids-from coll-k) the-uuid))))

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
         changes       (remove (comp #{::prepare
                                       ::defaults} first) changes)
         doc           (prepare doc)
         apply-changes (fn [doc]
                         (try
                           (some->> (reduce (fn [m [a f]]
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
                                    (merge defaults))
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
                     [v {:domain-name/name (unmunge-domain-name name)}]))))
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
           (mapcat (juxt :board/member-fields :board/project-fields))
           (mapcat identity)
           (map (juxt :field/id identity))
           (into {}))))

(defn prose [s]
  (when-not (str/blank? s)
    {:prose/format (if (str/includes? s "<")
                     :prose.format/html
                     :prose.format/markdown)
     :prose/string s}))

(defn video-value [v]
  (when (and v (not (str/blank? v)))
    (cond (re-find #"vimeo" v) v
          (re-find #"youtube" v) v
          :else (str "https://www.youtube.com/watch?v=" v))))

(defn parse-fields [managed-by-k to-k]
  (fn [m]
    (if-some [field-ks (->> (keys m)
                            (filter #(str/starts-with? (name %) "field_"))
                            seq)]
      (let [managed-by (managed-by-k m)]
        (try
          (reduce (fn [m k]
                    (let [field-id    (composite-uuid :field
                                                      (to-uuid :board managed-by)
                                                      (to-uuid :field (subs (name k) 6)))
                          v           (m k)
                          field-type  (:field/type (@!all-fields field-id))
                          ;; NOTE - we ignore fields that do not have a spec
                          entry-value (when field-type
                                        (case field-type
                                          :field.type/image-list (let [v (cond-> v (string? v) vector)]
                                                                   (when (seq v)
                                                                     (let [assets (mapv assets/link-asset v)]
                                                                       {:image-list/images (mapv #(select-keys % [:entity/id]) assets)})))
                                          :field.type/link-list {:link-list/links
                                                                 (mapv #(rename-keys % {:label :text
                                                                                        :url   :url}) v)}
                                          :field.type/select {:select/value v}
                                          :field.type/prose (prose v)
                                          :field.type/video (video-value v)
                                          (throw (Exception. (str "Field type not found "
                                                                  {:field/type    field-type
                                                                   :field-spec/id field-id
                                                                   })))))]
                      (-> (dissoc m k)
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
  (first @!tag->id))

(defn resolve-tag [board-id tag-string]
  (if-let [tag-id (get-in @!tag->id [board-id (str/lower-case tag-string)])]
    (when-not (= :NO_TAG tag-id)
      {:tag/id tag-id})
    {:tag/label tag-string}))

(defn mongo-id? [x]
  (try (do (bson-id-timestamp x) true)
       (catch Exception e false)))

(defn add-kind [kind]
  #(assoc % :entity/kind kind))

(def changes {:board/as-map
              [::prepare (partial flat-map :entity/id (partial to-uuid :board))
               ::always lookup-domain
               ::defaults {:board/registration-open? true
                           :entity/public?           true
                           :entity/parent            (uuid-ref :org "base")}
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
                                (let [managed-by (:entity/id m)]
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
                                                                                                                      "color" :field-option/color)))))})
                                                                 (u/assoc-some :field/default-value (->> (m "options")
                                                                                                         (filter #(get % "default"))
                                                                                                         first
                                                                                                         (get "value")))
                                                                 (update :field/type parse-field-type)))))
                                                (catch Exception e (prn a v) (throw e))))))]
                 ["groupFields" (& field-xf (rename :board/project-fields))
                  "userFields" (& field-xf (rename :board/member-fields))])

               "groupNumbers" (rename :board/project-numbers?)
               "projectNumbers" (rename :board/project-numbers?)
               "userMaxGroups" (& (xf #(Integer. %)) (rename :board/max-projects-per-member))
               "stickyColor" (rename :board/sticky-color)
               "tags" (& (fn [m a v]
                           (let [board-ref (uuid-ref :board (:entity/id m))]
                             (assoc m a (->> v
                                             (sort-by first)
                                             (flat-map :tag/id #(composite-uuid :tag
                                                                                (to-uuid :board board-ref)
                                                                                (to-uuid :tag %)))
                                             (map #(-> %
                                                       (dissoc "order")
                                                       (rename-keys {"color"    :tag/background-color
                                                                     "name"     :tag/label
                                                                     "label"    :tag/label
                                                                     "restrict" :tag/restricted?})
                                                       (u/update-some {:tag/restricted? (constantly true)})))
                                             (filter :tag/label)
                                             vec))))
                         (rename :board/member-tags))
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
               "registrationOpen" (rename :board/registration-open?)
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
               "learnMoreLink" (rename :entity/website)
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
                                                            :let [account (parse-sparkboard-id user)
                                                                  ent     (parse-sparkboard-id ent)
                                                                  _       (assert (= (:kind account) :account))]]
                                                        {:entity/id      (composite-uuid :member (:uuid ent) (:uuid account))
                                                         :entity/kind    :member
                                                         :member/account (uuid-ref :account (:uuid account))
                                                         :member/entity  (:ref ent)
                                                         :member/roles   (into #{}
                                                                               (comp (filter val)
                                                                                     (map key)
                                                                                     (map role-kw))
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
                                                                                      :entity/created-at (-> account :createdAt Long/parseLong time/instant Date/from)
                                                                                      :image/avatar (when-let [src (or (some-> (:picture most-recent-member-doc)
                                                                                                                               (u/guard #(not (str/starts-with? % "/images"))))
                                                                                                                       (:photoUrl account)
                                                                                                                       (:photoUrl provider))]
                                                                                                      (assets/link-asset src))
                                                                                      :account/display-name (:name most-recent-member-doc)
                                                                                      :account/email (:email account)
                                                                                      :account/email-verified? (:emailVerified account)
                                                                                      :account/last-sign-in (some-> account :lastSignedInAt Long/parseLong time/instant Date/from)
                                                                                      :account/password-hash (:passwordHash account)
                                                                                      :account/password-salt (:salt account)
                                                                                      :account.provider.google/sub (:rawId provider))))))))]
              :ballot/as-map          [::prepare (fn [users]
                                                   (->> users
                                                        (mapcat (fn [{:keys [_id boardId votesByDomain]}]
                                                                  (for [[domain project-id] votesByDomain
                                                                        :let [project-id (to-uuid :project project-id)
                                                                              board-id   (to-uuid :board boardId)
                                                                              account-id (member->account-uuid _id)]
                                                                        :when account-id]
                                                                    {:ballot/key     (str/join "+" [board-id account-id project-id])
                                                                     :ballot/account (uuid-ref :account account-id)
                                                                     :ballot/board   (uuid-ref :board board-id)
                                                                     :ballot/project (uuid-ref :project project-id)})))))
                                       ::always (remove-when #(or (missing-entity? :project/as-map (:ballot/project %))
                                                                  (missing-entity? :board/as-map (:ballot/board %))
                                                                  (not (:ballot/account %))))]
              :member/as-map          [::always (remove-when #(contains? #{"example" nil} (:boardId %)))
                                       ::always (remove-when (comp nil? :firebaseAccount))
                                       ::always (remove-when :entity/deleted-at)
                                       ::always (remove-when :suspectedFake)
                                       ::always (add-kind :member)

                                       ::defaults {:member/inactive?       false
                                                   :member/email-frequency :member.email-frequency/periodic}
                                       :lastModifiedBy rm
                                       :salt rm
                                       :hash rm
                                       :passwordResetToken rm
                                       :email rm            ;; in account
                                       :account rm
                                       :_id (fn [m a v]
                                              (-> m
                                                  (assoc :entity/created-at (bson-id-timestamp (get-oid v))
                                                         :entity/id (composite-uuid :member
                                                                                    (to-uuid :board (:boardId m))
                                                                                    (to-uuid :account (:firebaseAccount m))))
                                                  (dissoc a)))
                                       :boardId (uuid-ref-as :board :member/entity)

                                       ::always (parse-fields :member/entity :entity/field-entries)
                                       :account rm

                                       :firebaseAccount (uuid-ref-as :account :member/account)

                                       :emailFrequency (& (xf #(case %
                                                                 "never" :member.email-frequency/never
                                                                 "daily" :member.email-frequency/daily
                                                                 "periodic" :member.email-frequency/periodic
                                                                 "instant" :member.email-frequency/instant
                                                                 :member.email-frequency/periodic))
                                                          (rename :member/email-frequency))
                                       :acceptedTerms rm
                                       :contact_me rm

                                       :first_time rm
                                       :ready rm
                                       ;; new feature - not-joining-a-project


                                       :name rm
                                       :roles (& (xf #(into #{} (keep role-kw) %))
                                                 (rename :member/roles))
                                       :tags (& (fn [m a v]
                                                  (let [tags (keep (partial resolve-tag (:member/entity m)) v)
                                                        {tags        true
                                                         custom-tags false} (group-by (comp boolean :entity/id) tags)]
                                                    (-> m
                                                        (u/assoc-seq :member/tags (mapv (comp uuid-ref :entity/id) tags))
                                                        (u/assoc-seq :member/ad-hoc-tags (vec custom-tags))
                                                        (dissoc :tags)))))

                                       :newsletterSubscribe (rename :member/newsletter-subscription?)
                                       :active (& (xf not) (rename :member/inactive?)) ;; same as deleted?
                                       :picture rm
                                       :votesByDomain rm
                                       :feedbackRating rm

                                       ]

              :discussion/as-map      [#_#_::prepare (fn [m]
                                                       (when-not (empty? (:posts m))
                                                         m))
                                       :_id (partial id-with-timestamp :discussion)
                                       :type rm

                                       :followers (&
                                                    (xf member->account-uuid)
                                                    (uuid-ref-as :account :discussion/followers))
                                       :parent (uuid-ref-as :project :discussion/project)
                                       ::always (remove-when (comp (partial missing-entity? :project/as-map) :discussion/project)) ;; prune discussions from deleted projects
                                       :posts (& (xf
                                                   (partial change-keys
                                                            [:_id (partial id-with-timestamp :post)
                                                             :parent rm
                                                             :user (&
                                                                     (xf member->account-uuid)
                                                                     (uuid-ref-as :account :entity/created-by))
                                                             ::always (remove-when (complement :entity/created-by))
                                                             ::always (remove-when (comp str/blank? :text))

                                                             :text (& (xf prose) (rename :post/text))

                                                             :doNotFollow (& (xf member->account-uuid)
                                                                             (uuid-ref-as :account :post/do-not-follow))
                                                             :followers (& (xf member->account-uuid)
                                                                           (uuid-ref-as :account :post/followers))
                                                             :comments (& (xf (partial change-keys
                                                                                       [:_id (partial id-with-timestamp :comment)
                                                                                        :user (& (xf member->account-uuid)
                                                                                                 (uuid-ref-as :account :entity/created-by))
                                                                                        ::always (remove-when (complement :entity/created-by))
                                                                                        :text (rename :comment/text)
                                                                                        ::always (remove-when (comp str/blank? :comment/text))
                                                                                        :parent rm]))
                                                                          (rename :post/comments))]))
                                                 (rename :discussion/posts))
                                       :boardId rm
                                       ::always (remove-when (comp empty? :discussion/posts))]
              :project/as-map         [::defaults {:entity/archived? false}

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
                                       :lastModifiedBy (& (xf member->account-uuid)
                                                          (uuid-ref-as :account :entity/modified-by))
                                       :tags rm             ;; no longer used - fields instead
                                       :number (rename :project/number)
                                       :badges (& (xf (partial mapv (partial hash-map :badge/label)))
                                                  (rename :project/badges)) ;; should be ref
                                       :active (& (xf not) (rename :entity/archived?))
                                       :approved (rename :project/approved?)
                                       :ready (rename :project/team-complete?)
                                       :members (&
                                                  (fn [project a v]
                                                    (let [project-id (:entity/id project)]
                                                      (assoc project a (keep
                                                                         (fn [{:as   member
                                                                               :keys [user_id]}]
                                                                           (when-let [account-id (member->account-uuid user_id)]
                                                                             (let [role (if (and (not (:role member))
                                                                                                 (= account-id (some->> (:entity/created-by project)
                                                                                                                        (to-uuid :account))))
                                                                                          :role/admin
                                                                                          (some-> (:role member) role-kw))]
                                                                               (merge {:entity/id      (composite-uuid :member project-id account-id)
                                                                                       :entity/kind    :member
                                                                                       :member/entity  (uuid-ref :project project-id)
                                                                                       :member/account (uuid-ref :account account-id)}
                                                                                      (when role {:member/roles #{role}})))))
                                                                         v))))
                                                  (rename :member/_entity))

                                       ::always (remove-when #(and (not (:entity/created-by %))
                                                                   (empty? (:members/_entity %))))

                                       :looking_for (& (xf (fn [ss] (mapv (partial hash-map :request/text) ss)))
                                                       (rename :project/open-requests))
                                       :sticky (rename :project/sticky?)
                                       :demoVideo (& (xf video-value)
                                                     (rename :entity/video))
                                       :discussion rm       ;; unused
                                       ]
              :notification/as-map    [::defaults {:notification/emailed? false}
                                       ::always (fn [m]
                                                  (-> m
                                                      (dissoc :targetViewed :notificationViewed)
                                                      (assoc :notification/viewed? (boolean (or (:targetViewed m)
                                                                                                (:notificationViewed m))))))
                                       :_id (partial id-with-timestamp :notification)
                                       ::always (remove-when
                                                  (fn notification-filter [m]
                                                    (let [day-threshold 180]
                                                      (or (:notification/viewed? m)
                                                          (> (-> (:entity/created-at m) date-time days-since)
                                                             day-threshold)))))

                                       :createdAt rm
                                       :boardId (uuid-ref-as :board :notification/board)
                                       :recipientId (& (xf member->account-uuid)
                                                       (uuid-ref-as :account :notification/recipient))
                                       ::always (remove-when (complement :notification/recipient))
                                       :notificationEmailed (rename :notification/emailed?)
                                       :data (fn [m a v] (-> m (dissoc a) (merge v)))
                                       :project (& (xf :id)
                                                   (uuid-ref-as :project :notification/project))
                                       :user (& (xf :id)
                                                (xf member->account-uuid)
                                                (uuid-ref-as :account :notification/account))
                                       :message (& (xf :body)
                                                   (rename :notification/chat.new-message.text)
                                                   )
                                       :comment (& (xf :id)
                                                   (uuid-ref-as :comment :notification/post.comment))
                                       :post (& (xf :id)
                                                (uuid-ref-as :post :notification/post))
                                       :discussion (& (xf :id)
                                                      (uuid-ref-as :discussion :notification/discussion))
                                       ::always (fn [m]
                                                  (let [{:keys [type targetId]} m]
                                                    (-> m
                                                        (dissoc :type :targetId :targetPath)
                                                        (merge (case type
                                                                 "newMember" {:notification/type :notification.type/project.new-member}
                                                                 "newMessage" {:notification/type :notification.type/chat.new-message
                                                                               :notification/chat (uuid-ref :chat (get-oid targetId))}
                                                                 "newPost" {:notification/type :notification.type/discussion.new-post}
                                                                 "newComment" {:notification/type :notification.type/discussion.new-comment})))))
                                       ::always (remove-when #(or (missing-entity? :project/as-map (:notification/project %))
                                                                  (not (:notification/account %))
                                                                  (missing-entity? :chat/as-map (:notification/chat %))
                                                                  (missing-entity? :post/as-map (:notification/post %))
                                                                  (missing-entity? :discussion/as-map (:notification/discussion %))))
                                       :notification/board rm

                                       ]
              :chat/as-map            [:_id (partial id-with-timestamp :chat)
                                       ::always (remove-when #(contains? #{"example" nil} (:boardId %)))
                                       ::always (fn [m]
                                                  (assoc m :chat/entity (uuid-ref :board (@!member-id->board-id
                                                                                           (get-oid (first (:participantIds m)))))))
                                       :participantIds (& (xf #(let [out (member->account-uuid %)]
                                                                 (when (= (count out) (count %))
                                                                   out)))
                                                          (uuid-ref-as :account :chat/participants))
                                       ::always (remove-when (complement :chat/participants))
                                       :createdAt (& (xf parse-mongo-date)
                                                     (rename :entity/created-at))

                                       :modifiedAt (& (xf parse-mongo-date) (rename :entity/updated-at))

                                       ;; TODO - :messages
                                       :messages (& (xf (partial change-keys [:_id (partial id-with-timestamp :message)
                                                                              :createdAt rm
                                                                              ::always (remove-when (comp str/blank? :body))
                                                                              :body (& (xf prose)
                                                                                       (rename :chat.message/content))
                                                                              :senderId (& (xf member->account-uuid)
                                                                                           (uuid-ref-as :account :entity/created-by))
                                                                              :senderData rm]))
                                                    (rename :chat/messages))
                                       :readBy (fn [m a v]
                                                 (-> m
                                                     (dissoc a)
                                                     (assoc :chat/read-last
                                                            (let [last-id (->> (:chat/messages m)
                                                                               (sort-by :entity/created-at)
                                                                               last
                                                                               :entity/id)]
                                                              (into {}
                                                                    (comp (filter val)
                                                                          (keep (fn [[k v]]
                                                                                  (when v
                                                                                    [(member->account-uuid (name k))
                                                                                     last-id]))))
                                                                    v)))))
                                       :boardId rm
                                       ::always (remove-when (comp empty? :chat/messages))
                                       ::always (remove-when (comp nil? :chat/entity))
                                       ::always (fn [{:as m :keys [chat/participants chat/entity]}]
                                                  (assoc m :chat/key (apply chat/make-key entity participants)))]
              ::mongo                 [:deleted (& (xf (fn [x] (when x deletion-time)))
                                                   (rename :entity/deleted-at))
                                       ::always (remove-when :entity/deleted-at)
                                       :updatedAt (& (xf parse-mongo-date) (rename :entity/updated-at))
                                       :intro (& (xf prose)
                                                 (rename :entity/description))
                                       :owner (& (xf member->account-uuid)
                                                 (uuid-ref-as :account :entity/created-by))

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
                                                              (assoc :project/sticky? true)))))


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

(def coll-entities
  "Converts doc according to `changes`"
  (memoize
    (fn [k]
      (try
        (->> (read-coll k)
             (change-keys (changes-for k)))
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

(defn root-entities
  "Entities representing docs from the original coll. May contain nested (inline) entities."
  []
  (into []
        (mapcat coll-entities)
        colls))

(defn flatten-entities-xf []
  "Walks entities to pull out nested relations (eg. where a related entity is stored 'inline')"
  (let [reverse-ks (into #{} (filter #(str/starts-with? (name %) "_")) (keys @sch/!schema))]
    (mapcat (fn [doc]
              (cons (apply dissoc doc reverse-ks)
                    (mapcat doc reverse-ks))))))

(defn all-entities
  "Flat list of all entities (no inline nesting)" []
  (into []
        (flatten-entities-xf)
        (root-entities)))

(defn contains-somewhere?
  "Deep walk of a data structure to see if `v` exists anywhere inside it (via =)"
  [v coll]
  (let [!found (atom false)]
    (walk/postwalk #(do (when (= v %) (reset! !found true)) %) coll)
    @!found))

(comment

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

  (clojure.core/time (count (root-entities)))
  (firebase-account->email "m_5898a0e8f869e80400b2cfef" :foo)

  #_(def missing (->> (root-entities)
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

