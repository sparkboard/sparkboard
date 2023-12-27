(ns sb.app.form.ui
  (:require [applied-science.js-interop :as j]
            [inside-out.forms]
            [inside-out.forms :as io]
            [sb.app.entity.data :as entity.data]
            [sb.app.views.ui :as ui]
            [sb.color :as color]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.util :as u]
            [yawn.view :as v]))


(defn field-id [?field]
  #?(:cljs
     (str "field-" (goog/getUid ?field))
     :clj
     (str "field-" (:sym ?field))))


(defn pass-props [props]
  (reduce (fn [m k]
            (cond-> m
                    (qualified-keyword? k)
                    (dissoc k)))
          props
          (keys props)))

(defn get-label [?field & [label]]
  (u/some-or label
             (:field/label ?field)
             (when-let [a (:attribute ?field)]
               (sb.i18n/tr* (keyword "tr" (name a))))))

(defn show-label [?field & [label]]
  (when-let [label (get-label ?field label)]
    [:label.field-label {:for (field-id ?field)} label]))

(defn ?field-props [?field
                    {:keys [field/event->value
                            field/wrap
                            field/unwrap
                            on-change
                            save-on-change?]
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

(ui/defview filter-field [?field attrs]
  (let [loading? (or (:loading? ?field) (:loading? attrs))]
    [:div.flex.relative.items-stretch.flex-auto
     [:input.pr-9.border.border-gray-300.w-full.rounded-lg.p-3
      (-> (?field-props ?field {:event->props (j/get-in [:target :value])})
          (v/merge-props
            {:class       "outline-none focus-visible:outline-4 outline-offset-0 focus-visible:outline-gray-200"
             :placeholder "Search..."
             :on-key-down #(when (= "Escape" (.-key ^js %))
                             (reset! ?field nil))})
          (update :value #(or % "")))]
     [:div.absolute.top-0.right-0.bottom-0.flex.items-center.pr-3
      {:class "text-txt/40"}
      (cond loading? (icons/loading "w-4 h-4 rotate-3s")
            (seq @?field) [:div.contents.cursor-pointer
                           {:on-click #(reset! ?field nil)}
                           (icons/close "w-5 h-5")]
            :else (icons/search "w-6 h-6"))]]))