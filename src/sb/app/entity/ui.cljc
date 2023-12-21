(ns sb.app.entity.ui
  (:require [clojure.string :as str]
            [inside-out.forms :as forms]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.entity.data :as data]
            [sb.app.field.ui :as field.ui]
            [sb.routing :as routing]
            [sb.app.views.ui :as ui]
            [sb.icons :as icons]
            [sb.validate :as validate]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [sb.schema :as sch]))

(defn infer-view [attribute]
  (let [{:keys [malli/schema]} (get @sch/!schema attribute)]
    (case schema
      :string field.ui/text-field
      :http/url field.ui/text-field
      :boolean field.ui/checkbox-field
      :prose/as-map field.ui/prose-field
      nil)))

(defn use-persisted [entity attribute & {:as props :keys [view]}]
  #?(:cljs
     (let [persisted-value (get entity attribute)
           ?field          (h/use-memo #(forms/field :init persisted-value
                                                     :attribute attribute
                                                     props)
                                       ;; create a new field when the persisted value changes
                                       (h/use-deps persisted-value))
           view            (or view
                               (:view ?field)
                               (infer-view attribute) (throw (ex-info (str "No view declared for attribute: " attribute) {:attribute attribute})))]
       [view ?field (merge {:persisted-value persisted-value
                            :on-save         #(forms/try-submit+ ?field
                                                (data/save-attribute! nil (:entity/id entity) attribute %))}
                           (dissoc props :view))])))

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
                 {:style {:background-image (asset.ui/css-url (asset.ui/asset-src avatar :avatar))}})))])
   [:div.flex.items-center.px-3.leading-snug
    [:div.line-clamp-2 title]]])

(ui/defview settings-button [entity]
  (when-let [path (and (validate/editing-role? (:member/roles entity))
                       (some-> (routing/entity-route entity :settings) routing/path-for))]
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