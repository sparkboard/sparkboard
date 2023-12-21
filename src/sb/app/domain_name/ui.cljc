(ns sb.app.domain-name.ui
  (:require [clojure.string :as str]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [sb.app.domain-name.data :as data]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [tr]]))

#?(:cljs
   (defn availability-validator []
     (-> (fn [v {:keys [field]}]
           (when (not= (:domain-name/name (:init field))
                       (:domain-name/name v))
             (when-let [v (:domain-name/name v)]
               (when (>= (count v) 3)
                 (p/let [res (data/check-availability {:domain v})]
                   (if (:available? res)
                     (forms/message :info
                                    [:span.text-green-500.font-bold (tr :tr/available)])
                     (forms/message :invalid
                                    (tr :tr/not-available)
                                    {:visibility :always})))))))
         (forms/debounce 300))))


(ui/defview domain-field [?domain props]
  [:div.field-wrapper
   [form.ui/show-label ?domain]
   [:div.flex.gap-2.items-stretch
    (field.ui/text-field ?domain (merge {:wrap          (fn [v]
                                                          (when-not (str/blank? v)
                                                            {:domain-name/name (data/qualify-domain (data/normalize-domain v))}))
                                         :unwrap        (fn [v]
                                                          (or (some-> v :domain-name/name data/unqualify-domain) ""))
                                         :auto-complete "off"
                                         :spell-check   false
                                         :wrapper-class "flex-auto"}
                                        props
                                        {:label false}))
    [:div.flex.items-center.text-sm.text-gray-500.h-10 ".sparkboard.com"]]])

(defn validators []
  [data/domain-valid-string
   #?(:cljs (availability-validator))])