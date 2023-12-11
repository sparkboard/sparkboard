(ns sparkboard.app.entity
  (:require [inside-out.forms :as forms]
            [sparkboard.schema :as sch :refer [s- unique-uuid ?]]
            [sparkboard.ui.icons :as icons]
            [sparkboard.ui.radix :as radix]
            [yawn.hooks :as h]
            [sparkboard.query :as q]
            [sparkboard.authorize :as az]
            [re-db.api :as db]
            [sparkboard.validate :as validate]
            [sparkboard.ui :as ui]
            [yawn.view :as v]
            [sparkboard.routing :as routing]
            [malli.util :as mu]))

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
     :entity/kind               {s- [:enum :board :org :collection :member :project :chat :chat.message :field]}
     :entity/draft?             {:doc "Entity is not yet published - visible only to creator/team"
                                 s- :boolean}
     :entity/description        {:doc "Description of an entity (for card/header display)"
                                 s-   :prose/as-map
                                 #_#_:db/fulltext true}
     :entity/field-entries      (merge (sch/ref :many :field-entry/as-map)
                                       sch/component)
     :entity/video              {:doc "Primary video for project (distinct from fields)"
                                 s-   :video/entry}
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
              {:entity/domain [:domain/name]}])

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

(q/defx save-attribute!
  {:prepare [az/with-account-id!]}
  [ctx e a v]
  (save-attributes! ctx e {a v}))

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

;; how to determine if a field is required or optional in a malli map schema?


#?(:cljs
   (defn use-persisted [entity attribute field-view & [props]]
     (let [persisted-value (get entity attribute)
           ?field          (h/use-memo #(forms/field :init persisted-value
                                                     :attribute attribute
                                                     props)
                                       ;; create a new field when the persisted value changes
                                       (h/use-deps persisted-value))]
       [field-view ?field (merge {:persisted-value persisted-value
                                  :on-save         #(forms/try-submit+ ?field
                                                      (save-attribute! nil (:entity/id entity) attribute %))}
                                 props)])))

#?(:cljs
   (defn href [{:as e :entity/keys [kind id]} key]
     (when e
       (let [tag (keyword (name kind) (name key))]
         (routing/path-for [tag {(keyword (str (name kind) "-id")) id}])))))


(ui/defview card:compact
  {:key :entity/id}
  [{:as   entity
    :keys [entity/title image/avatar]}]
  [:a.flex.relative
   {:href  (routing/path-for (routing/entity-route entity :show))
    :class ["sm:divide-x sm:shadow sm:hover:shadow-md "
            "overflow-hidden rounded-lg"
            "h-12 sm:h-16 bg-card text-card-txt border border-white"]}
   (when avatar
     [:div.flex-none
      (v/props
        (merge {:class ["w-12 sm:w-16"
                        "bg-no-repeat sm:bg-secondary bg-center bg-contain"]}
               (when avatar
                 {:style {:background-image (ui/css-url (ui/asset-src avatar :avatar))}})))])
   [:div.flex.items-center.px-3.leading-snug
    [:div.line-clamp-2 title]]])


(ui/defview settings-button [entity]
  (when-let [path (and (validate/editing-role? (:member/roles entity))
                       (some-> (routing/entity-route entity :settings) routing/path-for))]
    [:a.icon-light-gray.flex.items-center.justify-center.focus-visible:bg-gray-200.self-stretch.rounded
     {:href path}
     [icons/gear "icon-lg"]]))

(ui/defview row
  {:key :entity/id}
  [{:as   entity
    :keys [entity/title image/avatar member/roles]}]
  [:div.flex.hover:bg-gray-100.rounded-lg
   [:a.flex.relative.gap-3.items-center.p-2.cursor-default.flex-auto
    {:href (routing/entity entity :show)}
    [ui/avatar {:size 10} entity]
    [:div.line-clamp-2.leading-snug.flex-grow title]]])

(ui/defview show-filtered-results
  {:key :title}
  [{:keys [q title results]}]
  (when-let [results (seq (sequence (ui/filtered q) results))]
    [:div.mt-6 {:key title}
     (when title [:div.px-body.font-medium title [:hr.mt-2.sm:hidden]])
     (into [:div.card-grid]
           (map card:compact)
           results)]))