(ns sparkboard.entities.domain
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.views.ui :as ui]))

(defn qualify-domain [domain]
  (when domain
    (if (str/includes? domain ".")
      domain
      (str domain ".sparkboard.com"))))

(defn unqualify-domain [domain]
  (when domain
    (str/replace domain #"\.sparkboard\.com$" "")))

(defn availability [req {{:keys [domain]} :query-params}]
  (let [domain (qualify-domain domain)]
    {:body {:available?
            (and (re-matches #"^[a-z0-9-.]+$" domain)
                 (nil? (db/get [:domain/name domain] :domain/name)))
            :domain domain}}))


(defn domain-valid-string [v _]
  (when-let [v (unqualify-domain (:domain/name v))]
    (when (< (count v) 3)
      (tr :tr/too-short))
    (when-not (re-matches #"^[a-z0-9-]+$" v)
      (forms/message :invalid
                     (tr :tr/invalid-domain)
                     {:visibility :always}))))

#?(:cljs
   (defn domain-availability-validator []
     (-> (fn [v {:keys [field]}]
           (when (not= (:init field) v)
             (when-let [v (:domain/name v)]
               (when (>= (count v) 3)
                 (p/let [res (routes/GET :domain/availability :query-params {:domain v})]
                   (if (:available? res)
                     (forms/message :info
                                    [:span.text-green-500.font-bold (tr :tr/available)])
                     (forms/message :invalid
                                    (tr :tr/not-available)
                                    {:visibility :always})))))))
         (forms/debounce 300))))

#?(:cljs 
   (defn show-domain-field [?domain]
     (ui/show-field ?domain {:label (tr :tr/domain-name)
                             :auto-complete "off"
                             :spell-check false
                             :placeholder (or (:placeholder ?domain) "<your-subdomain>")
                             :postfix [:span.text-sm.text-gray-500 ".sparkboard.com"]
                             :on-change (fn [^js e]
                                          (reset! ?domain {:domain/name (qualify-domain (.. e -target -value))}))
                             :value (or (unqualify-domain (:domain/name @?domain)) "")})))