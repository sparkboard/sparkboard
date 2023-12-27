(ns sb.app.domain-name.ui
  (:require [inside-out.forms :as io]
            [promesa.core :as p]
            [sb.app.domain-name.data :as data]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [t]]
            [sb.util :as u]))

#?(:cljs
   (defn availability-validator []
     (-> (fn [v {:as what :keys [field]}]
           (when (and v
                      (not= v (:init field))
                      (>= (count v) 3))
             (p/let [res (data/check-availability {:domain v})]
               (if (:available? res)
                 (io/message :info
                             [:span.text-green-500.font-bold (t :tr/available)])
                 (io/message :invalid
                             (t :tr/not-available)
                             {:visibility :always})))))
         (io/debounce 300))))


(defn make-domain-field [init _props]
  (io/form {:domain-name/name
            (some-> domain-name/?name
                    u/some-str
                    data/normalize-domain
                    data/qualify-domain)}
           :meta {domain-name/?name {:init (or (some-> init
                                                       :domain-name/name
                                                       data/unqualify-domain)
                                               "")
                                     :validators [data/domain-valid-string
                                                  #?(:cljs (availability-validator))]}}
           ))

(ui/defview domain-field [{:as ?field :syms [domain-name/?name]} props]
  [:div.field-wrapper
   [form.ui/show-label ?field (:field/label props)]
   [:div.flex.gap-2.items-stretch
    (field.ui/text-field ?name (merge props
                                      {:auto-complete       "off"
                                       :spell-check         false
                                       :field/wrapper-class "flex-auto"
                                       :field/label         false}))
    [:div.flex.items-center.text-sm.text-gray-500.h-10 ".sparkboard.com"]]])