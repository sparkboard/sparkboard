(ns sparkboard.app.domain.ui
  (:require [clojure.string :as str]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [sparkboard.app.domain.data :as data]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.ui :as ui]))

#?(:cljs
   (defn availability-validator []
     (-> (fn [v {:keys [field]}]
           (when (not= (:domain/name (:init field))
                       (:domain/name v))
             (when-let [v (:domain/name v)]
               (when (>= (count v) 3)
                 (p/let [res (data/check-availability {:domain v})]
                   (if (:available? res)
                     (forms/message :info
                                    [:span.text-green-500.font-bold (tr :tr/available)])
                     (forms/message :invalid
                                    (tr :tr/not-available)
                                    {:visibility :always})))))))
         (forms/debounce 300))))

#?(:cljs
   (defn domain-field [?domain & [props]]
     [ui/input-wrapper
      [ui/show-label ?domain]
      [:div.flex.gap-2.items-stretch
       (ui/text-field ?domain (merge {:wrap          (fn [v]
                                                       (when-not (str/blank? v)
                                                         {:domain/name (data/qualify-domain (data/normalize-domain v))}))
                                      :unwrap        (fn [v]
                                                       (or (some-> v :domain/name data/unqualify-domain) ""))
                                      :auto-complete "off"
                                      :spell-check   false
                                      :wrapper-class "flex-auto"}
                                     props
                                     {:label false}))
       [:div.flex.items-center.text-sm.text-gray-500.h-10 ".sparkboard.com"]]]))

#?(:cljs
   (defn validators []
     [data/domain-valid-string
      (availability-validator)]))