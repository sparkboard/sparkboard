(ns sparkboard.schema
  (:refer-clojure :exclude [ref keyword])
  (:require [malli.core :as m]
            [malli.error :refer [humanize]]
            [malli.registry :as mr]
            [re-db.schema :as s]
            [sparkboard.util :as u]))

(defn wrap-id [id]
  (if (uuid? id)
    [:entity/id id]
    id))

(defn unwrap-id [id]
  (if (vector? id)
    (second id)
    id))

(def s- :malli/schema)

(defn string-lookup-ref [ks]
  [:tuple (into [:enum] ks) :string])

(def db-id [:or
            :int
            [:tuple :qualified-keyword [:or :string :uuid]]
            [:map {:closed true} [:db/id :int]]])

(defn ref
  "returns a schema entry for a ref (one or many)"
  ([cardinality]
   (case cardinality :one (merge s/ref
                                 s/one
                                 {s- db-id})
                     :many (merge s/ref
                                  s/many
                                  {s- [:sequential db-id]})))
  ([cardinality nesting-schema]
   {:pre [(keyword? nesting-schema)]}
   (case cardinality :one (merge s/ref
                                 s/one
                                 {s- (conj db-id nesting-schema)})
                     :many (merge s/ref
                                  s/many
                                  {s- [:sequential
                                       (conj db-id nesting-schema)]}))))

(def unique-id-str (merge s/unique-id
                          s/string
                          {s- :string}))

(def unique-uuid (merge s/unique-id
                        s/uuid
                        {s- :uuid}))


(def unique-value s/unique-value)
(def unique-id s/unique-id)
(def component s/component)
(def instant s/instant)
(def keyword s/keyword)
(def many s/many)
(def string s/string)


(defn ? [k]
  (if (keyword? k)
    [k {:optional true}]
    (do (assert (vector? k))
        (if (map? (second k))
          (update k 1 assoc :optional true)
          (into [(first k) {:optional true}] (rest k))))))

(defn update-attrs [schema f & args]
  (if (ident? schema)
    [schema (apply f {} args)]
    (do (assert (vector? schema) (str "Not a vector: " schema))
        (if (map? (second schema))
          (update schema 1 #(apply f % args))
          (into [(first schema) (apply f {} args)] (rest schema))))))

(defn infer-db-type [m]
  (let [inferred-type (when (and (s- m) (not (:db/valueType m)))
                        (let [base-mappings {:string     s/string
                                             :boolean    s/boolean
                                             :keyword    s/keyword
                                             :http/url   s/string
                                             :html/color s/string
                                             :int        s/long #_s/bigint
                                             'inst?      s/instant}
                              known-bases   (set (keys base-mappings))
                              malli-type    (as-> (s- m) t
                                                  (cond-> t (vector? t) first))
                              malli-base    (or (known-bases malli-type)
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
    (merge inferred-type m)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; For bulk data import

(defonce !schema (atom {}))
(defonce !malli-registry (atom (m/default-schemas)))

;; validation for endpoints
;; - annotate endpoint functions with malli schema for :in and :out
;; - we need nice error messages

;; malli validator for a string with minimum length 8
;; malli email validator

;; http error code for invalid input
;; 400 bad request

(humanize
  (m/explain
    [:map
     [:x
      [:map
       [:y :int]]]]
    {:x {:y "foo"}}))

(defn register! [schema]
  (let [new-schema (update-vals schema infer-db-type)]
    (swap! !schema merge new-schema)
    nil))

(defn install-malli-schemas! []
  (reset! !malli-registry (merge (m/default-schemas) (update-vals @!schema s-)))
  (mr/set-default-registry! (mr/mutable-registry !malli-registry)))

(def !id-keys
  (delay
    (into #{}
          (comp (filter #(= :db.unique/identity (:db/unique (val %))))
                (map key))
          @!schema)))

(def !ref-keys
  (delay
    (into #{}
          (comp (filter (comp #{:db.type/ref} :db/valueType val))
                (map key))
          @!schema)))

(def !entity-schemas (delay (into {} (map (fn [k] [k (clojure.core/keyword (namespace k) "entity")])) @!id-keys)))

(defn unique-keys [m]
  (cond (map? m) (concat (some-> (select-keys m @!id-keys) (u/guard seq) list)
                         (->> (select-keys m @!ref-keys)
                              vals
                              (mapcat unique-keys)))
        (sequential? m) (mapcat unique-keys m)))

(defn entity-schema [m]
  (some @!entity-schemas (keys m)))

;; previously used - relates to :notification/subject-viewed?
(def notification-subjects {:notification.type/project.new-member     :entity/id
                            :notification.type/chat.new-message       :chat/id
                            :notification.type/discussion.new-post    :post/id
                            :notification.type/discussion.new-comment :post/id})


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; For validation of incomplete incoming data, e.g. to create an entity

(defn str-uuid? [x]
  (and (string? x)
       #?(:clj  (try #?(:clj (java.util.UUID/fromString x))
                     (catch java.lang.IllegalArgumentException _iae
                       nil))
          :cljs true)))

(register!
  (merge
    ;; webhooks
    {:webhook/event         {s- [:enum
                                 :event.board/update-member
                                 :event.board/new-member]}
     :webhook/subscriptions {s- [:map-of :webhook/event
                                 [:map {:closed true} :webhook/url]]}
     :webhook/url           {s- :http/url}}

    ;; i18n
    {:i18n/locale             {:doc "ISO 639-2 language code (eg. 'en')"
                               s-   [:re #"[a-z]{2}"]}
     :i18n/default-locale     {s- :string},
     :i18n/dict               {s- [:map-of :string :string]}
     :i18n/locale-suggestions {s- [:sequential :i18n/locale]}
     :i18n/locale-dicts       {:doc "Extra/override translations, eg. {'fr' {'hello' 'bonjour'}}",
                               s-   [:map-of :i18n/locale :i18n/dict]}}
    ;; util
    {:http/url       {s- [:re #"(?:[a-z]+?://)?.+\..+"]}
     ;; TODO - fix these in the db, remove "file" below
     :http/image-url {s- [:re #"(?i)https?://.+\..+\.(?:jpg|png|jpeg|gif|webp)$"]}
     :html/color     {s- :string}
     :email          {s- [:re {:error/message "should be a valid email"} #"^[^@]+@[^@]+$"]}}))

