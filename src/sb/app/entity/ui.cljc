(ns sb.app.entity.ui
  (:require [inside-out.forms :as io]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.entity.data :as data]
            [sb.app.views.ui :as ui]
            [sb.authorize :as az]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(defn malli-schema [a]
  (some-> (get @sch/!schema a) :malli/schema))

(defn throw-no-persistence! [?field]
  (throw (ex-info (str "No persistence for " (:sym ?field)) {:where (->> (iterate io/parent ?field)
                                                                         (take-while identity)
                                                                         (map :sym))})))

(def persisted-value data/persisted-value)

(defn view-field [?field & [props]]
  (let [view (or (:view props)
                 (:view ?field)
                 (some-> (:attribute ?field) io/global-meta :view)
                 (throw (ex-info (str "No view declared for field: " (:sym ?field) (:attribute ?field)) {:sym       (:sym ?field)
                                                                                                         :attribute (:attribute ?field)})))]
    [view ?field (merge (:props ?field)
                        (dissoc props :view))]))

(defn add-meta! [?field m]
  (swap! (io/!meta ?field) merge
         (when-let [attr (:attribute m)]
           (io/global-meta attr))
         m)
  (when-some [init (:init m)] (reset! ?field init))
  ?field)

(defn use-persisted-attr [e a & {:as props}]
  #?(:cljs
     (let [persisted-value (get e a)
           make-field      (or (:make-field (io/global-meta a))
                               (fn [init _props] (io/field :init init)))
           ?field          (h/use-memo #(doto (make-field persisted-value props)
                                          (add-meta! {:attribute        a
                                                      :db/id            (sch/wrap-id e)
                                                      :field/label      (:field/label props)
                                                      :field/persisted? true}))
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

(ui/defview settings-button [{:as entity :keys [member/roles]}]
  (when-let [path (and (or (:role/admin roles)
                           (:role/org-admin roles))
                       (some-> (routing/entity-route entity 'admin-ui/settings)
                               routing/path-for))]
    [:a.button
     {:href path}
     [icons/gear "icon-lg"]]))

(ui/defview row
  {:key :entity/id}
  [{:as   entity
    :keys [entity/title member/roles]}]
  [:div.flex.hover:bg-gray-100.rounded-lg
   [:a.flex.relative.gap-3.items-center.p-2.cursor-default.flex-auto
    {:href (routing/entity-path entity 'ui/show)}
    [ui/avatar {:size 10} entity]
    [:div.line-clamp-2.leading-snug.flex-grow.flex title]]])

(ui/defview show-filtered-results
  {:key :title}
  [{:keys [q title results]}]
  (when-let [results (seq (sequence (ui/filtered q) results))]
    [:div.mt-6 {:key title}
     (when title [:div.px-body.font-medium title [:hr.mt-2.sm:hidden]])
     (into [:div.card-grid]
           (map card:compact)
           results)]))