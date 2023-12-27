(ns sb.app.entity.data
  (:require [malli.util :as mu]
            [re-db.api :as db]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s- unique-uuid]]
            [sb.validate :as validate]
            [inside-out.forms :as io]))

(sch/register!
  (merge
    {:tag/id               unique-uuid
     :tag/background-color {s- :html/color},
     :tag/label            {s- :string},,
     :tag/restricted?      {:doc "Tag may only be modified by an admin of the owner of this tag"
                            s-   :boolean}
     :tag/as-map           {:doc "Description of a tag which may be applied to an entity."
                            s-   [:map {:closed true}
                                  :tag/id
                                  :tag/label
                                  (? :tag/background-color)
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
                                     :member
                                     :project
                                     :field
                                     :discussion
                                     :post
                                     :comment
                                     :notification
                                     :tag
                                     :chat
                                     :message
                                     :roles
                                     :account
                                     :ballot
                                     :site
                                     :asset
                                     :chat.message]}
     :entity/draft?             {:doc "Entity is not yet published - visible only to creator/team"
                                 s-   :boolean}
     :entity/description        {:doc "Description of an entity (for card/header display)"
                                 s-   :prose/as-map
                                 #_#_:db/fulltext true}
     :entity/field-entries      {s- [:map-of :uuid :field-entry/as-map]}
     :entity/fields             {s- [:sequential :field/as-map]}
     :entity/video              {:doc "Primary video for project (distinct from fields)"
                                 s-   :video/url}
     :entity/public?            {:doc "Contents of this entity can be accessed without authentication (eg. and indexed by search engines)"
                                 s-   :boolean}
     :entity/website            {:doc "External website for entity"
                                 s-   :http/url}
     :entity/social-feed        {s- :social/feed}
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
                                       {:doc "Member or account who created this entity"}),
     :entity/deleted-at         {:doc  "Date when entity was marked deleted"
                                 :todo "Excise deleted data after a grace period"
                                 s-    'inst?}
     :entity/modified-by        (merge (sch/ref :one)
                                       {:doc "Member who last modified this entity"}),
     :entity/updated-at         {s-   'inst?
                                 :doc "Date the entity was last modified"}}))

(def id-fields [:entity/id :entity/kind])

(def fields `[~@id-fields
              :entity/title
              :entity/description
              :entity/created-at
              :entity/deleted-at
              {:image/avatar [:entity/id]}
              {:image/background [:entity/id]}
              {:entity/domain-name [:domain-name/name]}])

(q/defx save-attributes!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} e m]
  (let [e             (sch/wrap-id e)
        _             (validate/assert-can-edit! e account-id)
        {:as entity :keys [entity/id entity/kind]} (db/entity e)
        parent-schema (-> (keyword (name kind) "as-map")
                          (@sch/!malli-registry))
        txs           [(assoc m :db/id e)]]
    (validate/assert m (mu/select-keys parent-schema (keys m)))
    (try
      (db/transact! txs)
      (catch Exception e (def E e) (throw e)))

    {:txs txs}))

(defn persisted-value [?field]
  (if-let [{:keys [db/id attribute]} (when (:field/persisted? ?field) ?field)]
    (get (db/entity id) attribute)
    (:init ?field)
    #_(throw-no-persistence! ?field)))

(q/defx save-attribute!
  {:prepare [az/with-account-id!]}
  [ctx e a v]
  (save-attributes! ctx e {a v}))

(defn save-field [?field]
  (when-let [{:as ?persisted-field :keys [db/id attribute]} (io/ancestor-by ?field :field/persisted?)]
    (io/try-submit+ ?persisted-field
      (save-attribute! nil id attribute @?persisted-field))))

(defn maybe-save-field
  [?field]
  (let [value @?field]
    (when (and (io/closest ?field :field/persisted?)
               (not= value (persisted-value ?field)))
      (io/try-submit+ ?field
        (save-field ?field)))))

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
