(ns sb.app.entity.ui
  (:require [clojure.string :as str]
            [inside-out.forms :as io]
            [re-db.api :as db]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.domain-name.ui :as domain.ui]
            [sb.app.entity.data :as data]
            [sb.app.field.ui :as field.ui]
            [sb.app.views.ui :as ui]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [sb.validate :as validate]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(defn infer-view [attribute]
  (when attribute
    (case attribute
      :entity/domain-name domain.ui/domain-field
      :image/avatar field.ui/image-field
      (let [{:keys [malli/schema]} (get @sch/!schema attribute)]
        (case schema
          :string field.ui/text-field
          :http/url field.ui/text-field
          :boolean field.ui/checkbox-field
          :prose/as-map field.ui/prose-field
          [:sequential :field/as-map] @(resolve 'sb.app.field.admin-ui/fields-editor)
          (cond (str/ends-with? (name attribute) "?") field.ui/checkbox-field))))))

(defn throw-no-persistence! [?field]
  (throw (ex-info (str "No persistence for " (:sym ?field)) {:where (->> (iterate io/parent ?field)
                                                                         (take-while identity)
                                                                         (map :sym))})))

(defn persisted-value [?field]
  (if-let [{:keys [entity attribute wrap]} (:field/persistence ?field)]
    (wrap (get entity attribute))
    (:init ?field)
    #_(throw-no-persistence! ?field)))

(defn save-field [?field & {:as props}]
  (if-let [{:keys [entity attribute wrap]} (io/closest ?field :field/persistence)]
    (io/try-submit+ ?field
      (data/save-attribute! nil
                            (sch/wrap-id entity)
                            attribute
                            (wrap @?field)))
    (throw-no-persistence! ?field)))

(defn view-field [?field & [props]]
  (let [view (or (:view props)
                 (:view ?field)
                 (some-> (:attribute ?field) infer-view)
                 (throw (ex-info (str "No view declared for field: " (:sym ?field) (:attribute ?field)) {:sym       (:sym ?field)
                                                                                                         :attribute (:attribute ?field)})))
        {:keys [entity attribute]} ?field]
    [view ?field (merge (:props ?field)
                        {:persisted-value (get entity attribute)
                         :on-save         (partial save-field ?field props)}
                        (dissoc props :view))]))

(defn add-meta! [?field m]
  (swap! (io/!meta ?field) merge
         (when-let [attr (:attribute m)]
           (io/global-meta attr))
          m)
  (when-some [init (:init m)] (reset! ?field init))
  ?field)

(defn use-persisted-field [e a & {:as props}]
  #?(:cljs
     (let [persisted-value (get e a)
           make-field (or (:make-field props)
                          (:make-field (io/global-meta a))
                          #(io/field))
           ?field (h/use-memo #(doto (make-field)
                                 (add-meta! {:init persisted-value
                                             :attribute         a
                                             :entity            e
                                             :wrap              (:wrap props identity)
                                             :field/persistence {:attribute a
                                                                 :entity    e
                                                                 :wrap      (:wrap props identity)}}))
                              ;; create a new field when the persisted value changes
                              (h/use-deps persisted-value))]
       (view-field ?field props))))

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

(ui/defview settings-button [entity]
  (when-let [path (and (validate/editing-role? (:member/roles entity))
                       (some-> (routing/entity-route entity 'admin-ui/settings)
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
    {:href (routing/entity-path entity 'ui/show)}
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