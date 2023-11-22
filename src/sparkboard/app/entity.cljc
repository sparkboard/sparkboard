(ns sparkboard.app.entity
  (:require [inside-out.forms :as forms]
            [sparkboard.schema :as sch :refer [s- unique-uuid ?]]
            [yawn.hooks :as h]
            [sparkboard.query :as q]
            [sparkboard.authorize :as az]
            [re-db.api :as db]
            [sparkboard.validate :as validate]
            [sparkboard.ui :as ui]
            [yawn.view :as v]
            [sparkboard.routes :as routes]
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
     :entity/kind               {s- [:enum :board :org :collection :member :project :chat :chat.message :field]}
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

(def fields [:entity/id
             :entity/kind
             :entity/title
             :entity/description
             :entity/created-at
             :entity/deleted-at
             {:image/avatar [:asset/id]}
             {:image/background [:asset/id]}
             {:entity/domain [:domain/name]}])

(q/defx save-attribute!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} e a v]
  (let [e             (sch/wrap-id e)
        _             (validate/assert-can-edit! e account-id)
        {:as entity :keys [entity/id entity/kind]} (db/entity e)
        pv            (get entity a)
        parent-schema (-> (keyword (if kind (name kind) (namespace a)) "as-map")
                          (@sch/!malli-registry))
        required?     (-> parent-schema
                          (@sch/!malli-registry)
                          (mu/find a)
                          (mu/-required-map-entry?))
        txs           (if (nil? v)
                        [[:db/retract e a]]
                        [{:db/id e a v}])]

    (when (and required? (nil? v))
      (validate/validation-failed! "Required field"))

    (when (some? v)
      (validate/assert {a v} (mu/select-keys parent-schema [a])))
    (try
      (db/transact! txs)
      (catch Exception e (def E e) (throw e)))

    {:db/id id}))

(q/defx set-order!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} e a order-attr side target destination]
  (let [target      (db/get (sch/wrap-id target) :db/id)
        destination (db/get (sch/wrap-id target) :db/id)
        siblings    (->> (db/get (sch/wrap-id e) a)
                         (sort-by order-attr)
                         (map :db/id)
                         (remove target)
                         (reduce (fn [out x]
                                   (if (= destination x)
                                     (into out (case side :before [target x]
                                                          :after [x target]))
                                     (conj out x))) []))
        txs (map-indexed (fn [i x]
                           [:db/add [:entity/id (db/get x :entity/id)] order-attr i]) siblings)]
    (prn :will-set txs)
    #_(db/transact! txs)
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
         (routes/href [tag {(keyword (str (name kind) "-id")) id}])))))


(ui/defview card:compact
  {:key :entity/id}
  [{:as   entity
    :keys [entity/title image/avatar]}]
  [:a.flex.relative
   {:href  (routes/href (routes/entity-route entity :show))
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

(ui/defview row
  {:key :entity/id}
  [{:as   entity
    :keys [entity/title image/avatar]}]
  [:a.flex.relative.gap-3.items-center.hover:bg-gray-100.rounded-lg.p-2.cursor-default
   {:href (routes/entity entity :show)}
   [ui/avatar {:size 10} entity]
   [:div.line-clamp-2.leading-snug title]])

(ui/defview show-filtered-results
  {:key :title}
  [{:keys [q title results]}]
  (when-let [results (seq (sequence (ui/filtered q) results))]
    [:div.mt-6 {:key title}
     (when title [:div.px-body.font-medium title [:hr.mt-2.sm:hidden]])
     (into [:div.card-grid]
           (map card:compact)
           results)]))