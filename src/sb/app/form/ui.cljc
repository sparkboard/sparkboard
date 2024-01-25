(ns sb.app.form.ui
  (:require [applied-science.js-interop :as j]
            [inside-out.forms]
            [inside-out.forms :as io]
            [sb.app.entity.data :as entity.data]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.authorize :as az]
            [sb.color :as color]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))


(defn field-id [?field]
  #?(:cljs
     (str "field-" (goog/getUid ?field))
     :clj
     (str "field-" (:sym ?field))))

(defn attribute-label [a]
  (sb.i18n/tr* (keyword "tr" (name a))))

(defn get-label [?field & [label]]
  (u/some-or label
             (:field/label ?field)
             (io/closest ?field #(some-> (:attribute %) attribute-label))))

(defn show-label [?field & [label class]]
  (when-let [label (get-label ?field label)]
    [:label.field-label {:for (field-id ?field) :class class} label]))

(defn ?field-props [?field
                    {:keys [field/event->value
                            field/wrap
                            field/unwrap
                            field/save-on-change?
                            on-change]
                     :or   {wrap   identity
                            unwrap identity}}]
  {:id        (field-id ?field)
   :value     (unwrap @?field)
   :on-change (fn [e]
                (let [new-value (wrap (event->value e))]
                  (reset! ?field new-value)
                  (when on-change
                    (on-change e))
                  (when save-on-change?
                    (entity.data/maybe-save-field ?field))))
   :on-blur   (fn [e]
                (reset! ?field (wrap (event->value e)))
                (entity.data/maybe-save-field ?field)
                ((io/blur-handler ?field) e))
   :on-focus  (io/focus-handler ?field)})

(def email-validator (fn [v _]
                       (when v
                         (when-not (re-find #"^[^@]+@[^@]+$" v)
                           (t :tr/invalid-email)))))

(def form-classes "flex-v gap-4 p-6 max-w-lg mx-auto bg-back relative text-sm")

(ui/defview view-message [{:keys [type content]}]
  (case type
    :in-progress (ui/loading:spinner " h-4 w-4 text-blue-600 ml-2")
    [:div
     {:style (case type
               (:error :invalid) {:color            color/invalid-text-color
                                  :background-color color/invalid-bg-color}
               nil)}
     content]))

(defn show-field-messages [?field]
  (when-let [messages (seq (io/visible-messages ?field))]
    (v/x (into [:div.gap-3.text-sm] (map view-message messages)))))

(ui/defview submit-form [?form label]
  [:<>
   (show-field-messages ?form)
   [:button.btn.btn-primary
    {:type     "submit"
     :disabled (not (io/submittable? ?form))}
    label]])

#?(:cljs
   (defn use-dev-panel [entity role-map default]
     (let [!roles    (h/use-state default)
           roles     (role-map @!roles)]
       [(az/editor-role? roles)
        roles
        (when (ui/dev?)
          [radix/select-menu {:value           @!roles
                              :on-value-change #(reset! !roles %)
                              :field/classes   {:trigger "flex items-center px-2 icon-gray text-sm self-start focus-visible:ring-0"
                                                :content (str radix/menu-content-classes " text-sm")}
                              :field/can-edit? true
                              :field/options   (->> (keys role-map)
                                                    (mapv (fn [k] {:value k :text k})))}])])))