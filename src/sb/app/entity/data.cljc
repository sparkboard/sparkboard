(ns sb.app.entity.data
  (:require [malli.util :as mu]
            [re-db.api :as db]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.server.datalevin :as dl]
            [sb.schema :as sch :refer [? s- unique-uuid]]
            [sb.validate :as validate]
            [inside-out.forms :as io]
            [clojure.set :as set]
            [sb.util :as u]))

(sch/register!
  (merge
    {:tag/id          unique-uuid
     :tag/color       {s- :html/color},
     :tag/label       {s- :string},,
     :tag/restricted? {:doc "Tag may only be modified by an admin of the owner of this tag"
                       s-   :boolean}
     :tag/as-map      {:doc "Description of a tag which may be applied to an entity."
                       s-   [:map {:closed true}
                             :tag/id
                             :tag/label
                             (? :tag/color)
                             (? :tag/restricted?)]}}

    {:entity/id                 unique-uuid
     :entity/title              {:doc         "Title of entity, for card/header display."
                                 s-           :string
                                 :db/fulltext true}
     :entity/parent             (sch/ref :one)
     :entity/kind               {s- [:enum
                                     :org
                                     :board
                                     :collection
                                     :membership
                                     :project
                                     :field
                                     :post
                                     :notification
                                     :tag
                                     :chat
                                     :message
                                     :roles
                                     :account
                                     :ballot
                                     :site
                                     :asset
                                     :chat.message
                                     :note]}
     :entity/project-fields     {s- :entity/fields}
     :entity/member-fields      {s- :entity/fields}
     :entity/member-tags        {s- [:sequential :tag/as-map]}
     :entity/draft?             {:doc "Entity is not yet published - visible only to creator/team"
                                 s-   :boolean}
     :entity/description        {:doc "Description of an entity (for card/header display)"
                                 s-   :prose/as-map
                                 #_#_:db/fulltext true}
     :entity/field-entries      {s- [:map-of :uuid :field-entry/as-map]}
     :entity/fields             {s- [:sequential :field/as-map]}
     :entity/video              {:doc "Primary video for project (distinct from fields)"
                                 s-   [:map {:closed true} :video/url]}
     :entity/public?            {:doc "Contents of this entity can be accessed without authentication (eg. and indexed by search engines)"
                                 s-   :boolean}
     :entity/social-feed        {s- :social/feed}
     :entity/uploads            (sch/ref :many)
     :entity/images             {s- [:map-of
                                     [:qualified-keyword {:namespace :image}]
                                     :http/url]}
     :entity/meta-description   {:doc "Custom description for html page header"
                                 s-   :string}
     :entity/locale-default     {s- :i18n/locale}
     :entity/locale-suggestions {:doc "Suggested locales (set by admin, based on expected users)",
                                 s-   :i18n/locale-suggestions}
     :entity/locale-dicts       {s- :i18n/locale-dicts}
     :entity/created-at         {s-   'inst?
                                 :doc "Date the entity was created"},
     :entity/created-by         (merge (sch/ref :one)
                                       {:doc "Account who created this entity"}),
     :entity/deleted-at         {:doc  "Date when entity was marked deleted. Should always be generated on the server because client clocks can't be trusted"
                                 :todo "Excise deleted data after a grace period"
                                 s-    'inst?}
     :entity/modified-by        (merge (sch/ref :one)
                                       {:doc "Account who last modified this entity"}),
     :entity/updated-at         {s-   'inst?
                                 :doc "Date the entity was last modified"}}))

(def id-fields [:entity/id :entity/kind])
(def listing-fields `[~@id-fields
                      :entity/title
                      :account/display-name
                      :entity/description
                      :entity/created-at
                      {:image/avatar [:entity/id]}])

(def entity-keys `[~@listing-fields
                   {:entity/video [:video/url]}
                   {:image/background [:entity/id]}
                   {:entity/domain-name [:domain-name/name]}])

(def site-fields [{:image/background [:entity/id]}
                  {:image/sub-header [:entity/id]}
                  {:entity/domain-name [:domain-name/name]}])

(defn required? [parent-schema child-attr]
  (-> parent-schema
      (mu/find child-attr)
      (mu/-required-map-entry?)))

(defn ignore-optional-nils [parent-schema m]
  (reduce-kv (fn [m k v]
               (if (and (nil? v) (not (required? parent-schema k)))
                 (dissoc m k)
                 m))
             m
             m))

(defn retract-nils
  [m]
  (let [nils (->> m (filter #(nil? (val %))) (map key))
        m    (apply dissoc m nils)
        e    (:db/id m)]
    (cond-> []
            (seq m) (conj m)
            (seq nils) (into (for [a nils] [:db/retract e a])))))

(def rules
  {:entity/tags (fn validate-changed-tags [roles k entity m]
                  (when-not (= :membership (:entity/kind entity))
                    (validate/validation-failed! "Only members may have tags"))
                  (let [tags-before  (into #{} (map :tag/id) (k entity))
                        tags-after   (into #{} (map :tag/id) (k m))
                        tags-changed (concat
                                       (set/difference tags-before tags-after) ;; removed
                                       (set/difference tags-after tags-before)) ;; added
                        admin?       (:role/board-admin roles)]
                    (when (seq tags-changed)
                      (let [tag-defs (-> entity :membership/entity :entity/member-tags (u/index-by :tag/id))]
                        (doseq [tag-id tags-changed         ;; added
                                :let [tag (get tag-defs tag-id)]
                                :when (:tag/restricted? tag)]
                          (when (and (not admin?) (:tag/restricted? tag))
                            (validate/permission-denied! "Only admins may modify restricted tags"))
                          (when (not tag)
                            (validate/validation-failed! (str "Tag " tag-id " does not exist"))))))))
   :membership/roles (fn [roles k entity m]
                       (when-not (az/admin-role? roles)
                         (validate/permission-denied!)))})

(q/defx save-attributes!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} e m]
  (let [e      (sch/wrap-id e)
        entity (dl/entity e)
        roles  (az/all-roles account-id entity)
        ;; TODO put this in :prepare with member.data/assert-can-edit ?
        _      (when-not (az/editor-role? roles)
                 (validate/permission-denied!))
        txs    (-> (assoc m :db/id e)
                   retract-nils)]
    (doseq [k (keys m)
            :let [rule (get rules k)]
            :when rule]
      (rule roles k entity m))

    (let [parent-schema (-> (keyword (name (:entity/kind entity)) "as-map")
                            (@sch/!malli-registry))
          without-nils  (ignore-optional-nils parent-schema m)]
      (validate/assert without-nils (mu/select-keys parent-schema (keys without-nils))))

    (try
      (db/transact! txs)
      (catch Exception e (def E e) (throw e)))

    {:txs txs}))

(q/defx delete!
  "Sets `:entity/deletat-at` to current instant.
We use this instead of `save-attributes!` because we do not trust the client's clock"
  {:prepare [az/with-account-id!
             (az/assert-can-admin-or-self :entity-id)]}
  [{:keys [entity-id]}]
  (db/transact! [{:entity/id entity-id
                  :entity/deleted-at (java.util.Date.)}])
  {:body ""})

(defn persisted-value [?field]
  (if-let [{:keys [db/id attribute]} (when (:field/persisted? ?field) ?field)]
    (get (db/entity id) attribute)
    (or (:init ?field)
        (:default ?field))
    #_(throw-no-persistence! ?field)))

(defn maybe-save-field
  [?field]
  (when-let [{:as ?persisted-field :keys [db/id attribute]} (io/ancestor-by ?field :field/persisted?)]
    (when (not= @?field (persisted-value ?field))
      (io/try-submit+ ?persisted-field
        (save-attributes! nil id {attribute @?persisted-field})))))

(defn reverse-attr [a]
  (keyword (namespace a) (str "_" (name a))))

(q/defx order-ref!
  {:prepare [az/with-account-id!]}
  [{:as   args
    :keys [account-id
           attribute
           order-by
           source
           side
           destination]}]
  (let [source         (db/entity source)
        source-id      (:db/id source)
        destination    (db/entity destination)
        destination-id (:db/id destination)
        parent         (-> source
                           (get (reverse-attr attribute))
                           first)
        sibling-ids    (->> (get parent attribute)
                            (sort-by order-by)
                            (map :db/id))
        siblings       (->> sibling-ids
                            (remove #{source-id})
                            vec
                            (reduce (fn [out destination]
                                      (if (= destination-id destination)
                                        (into out (case side :before [source-id destination]
                                                             :after [destination source-id]))
                                        (conj out destination))) []))
        txs            (map-indexed (fn [i x]
                                      [:db/add [:entity/id (db/get x :entity/id)] order-by i]) siblings)]
    (db/transact! txs)
    txs))
