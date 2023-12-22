(ns sb.app.entity.ui
  (:require [clojure.string :as str]
            [inside-out.forms :as io]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.entity.data :as data]
            [sb.app.field.ui :as field.ui]
            [sb.routing :as routing]
            [sb.app.views.ui :as ui]
            [sb.icons :as icons]
            [sb.validate :as validate]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [sb.schema :as sch]
            [re-db.api :as db]
            [sb.util :as u]))

(defn infer-view [attribute]
  (when attribute
    (let [{:keys [malli/schema]} (get @sch/!schema attribute)]
      (case schema
        :string field.ui/text-field
        :http/url field.ui/text-field
        :boolean field.ui/checkbox-field
        :prose/as-map field.ui/prose-field
        (cond (str/ends-with? (name attribute) "?") field.ui/checkbox-field)))))


(defn field-path [?field]
  (->> ?field
       (iterate io/parent)
       (take-while identity)
       (keep #(or (:attribute %) (:entity/id %)))
       reverse))

(defn persisted-value [?field]
  (let [[e & path] (field-path ?field)]
    (get-in (db/get e) path)))

(defn view-field [?field & [props]]
  (let [view (or (:view props)
                 (:view ?field)
                 (infer-view (:attribute ?field))
                 (throw (ex-info (str "No view declared for field: " (:sym ?field) (:attribute ?field)) {:sym       (:sym ?field)
                                                                                                         :attribute (:attribute ?field)})))
        [e a :as path] (field-path ?field)]
    [:div
     [view ?field (merge (dissoc props :view)
                         (when (and (= 2 (count path))
                                    (uuid? (first path)))
                           {:persisted-value (db/get e a)
                            :on-save #(io/try-submit+ ?field
                                        (do (prn :saving-pruned (u/prune @?field))
                                            (data/save-attribute! nil e a (u/prune @?field))))}))]]))

(defn use-persisted-attr [entity attribute & {:as props}]
  #?(:cljs
     (let [persisted-value (get entity attribute)
           ?field          (h/use-memo #(io/field :init persisted-value
                                                  :attribute attribute
                                                  props)
                                       ;; create a new field when the persisted value changes
                                       (h/use-deps persisted-value))]
       (view-field ?field (merge {:on-save (fn save-attr []
                                             (io/try-submit+ ?field
                                               (data/save-attribute! nil (:entity/id entity) attribute @?field)))
                                  :persisted-value persisted-value}
                                 props)))))

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
   {:href  (routing/path-for (routing/entity-route entity 'ui/show))
    :class ["sm:divide-x sm:shadow sm:hover:shadow-md "
            "overflow-hidden rounded-lg"
            "h-12 sm:h-16 bg-card text-card-txt border border-white"]}
   (when avatar
     [:div.flex-none
      (v/props
        (merge {:class ["w-12 sm:w-16"
                        "bg-no-repeat sm:bg-secondary bg-center bg-contain"]}
               (when avatar
                 {:style {:background-image (asset.ui/css-url (asset.ui/asset-src avatar :avatar))}})))])
   [:div.flex.items-center.px-3.leading-snug
    [:div.line-clamp-2 title]]])
routing/entity-route

(ui/defview settings-button [entity]
  (when-let [path (and (validate/editing-role? (:member/roles entity))
                       (some->  (routing/entity-route entity 'admin-ui/settings)
                                routing/path-for))]
    [:a.button
     {:href path}
     [icons/gear "icon-lg"]]))

(ui/defview row
  {:key :entity/id}
  [{:as   entity
    :keys [entity/title image/avatar member/roles]}]
  [:div.flex.hover:bg-gray-100.rounded-lg
   [:a.flex.relative.gap-3.items-center.p-2.cursor-default.flex-auto
    {:href (routing/entity-path entity :show)}
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