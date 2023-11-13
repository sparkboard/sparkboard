(ns sparkboard.app.domain
  (:require [clojure.string :as str]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.authorize :as az]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.schema :as sch :refer [s- ?]]
            [sparkboard.ui :as ui]
            [sparkboard.util :as u]
            [sparkboard.query :as q]))

(sch/register!
  {:domain/url     {s- :http/url}
   :domain/name    (merge {:doc "A complete domain name, eg a.b.com"}
                          sch/unique-id-str)
   :domain/owner   (sch/ref :one)
   :entity/domain  (merge (sch/ref :one :domain/as-map)
                          sch/unique-value)
   :entity/_domain {s- [:map {:closed true} :entity/id]}
   :domain/as-map  (merge (sch/ref :one)
                          {s- [:map {:closed true}
                               :domain/name
                               (? :entity/_domain)
                               (? :domain/url)
                               (? :domain/owner)]})})

(defn normalize-domain [domain]
  (-> domain
      (str/lower-case)
      (str/replace #"[\s-]+" "-")
      (str/replace #"[^a-z0-9-]+" "")))

(defn qualify-domain [domain]
  (when domain
    (if (str/includes? domain ".")
      domain
      (str domain ".sparkboard.com"))))

(defn unqualify-domain [domain]
  (when domain
    (str/replace domain #"\.sparkboard\.com$" "")))

(q/defx check-availability
  {:prepare [az/with-account-id!]}
  [{:keys [domain]}]
  (let [domain (qualify-domain domain)]
    {:available?
     (and (re-matches #"^[a-z0-9-.]+$" domain)
          (nil? (db/get [:domain/name domain] :domain/name)))
     :domain domain}))

(comment
  (into {} (db/entity [:domain/name "opengeneva.sparkboard.com"])))

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
                 (p/let [res (check-availability {:domain v})]
                   (if (:available? res)
                     (forms/message :info
                                    [:span.text-green-500.font-bold (tr :tr/available)])
                     (forms/message :invalid
                                    (tr :tr/not-available)
                                    {:visibility :always})))))))
         (forms/debounce 300))))

#?(:cljs
   (defn domain-field [?domain & [props]]
     (ui/text-field ?domain (merge {:wrap          (fn [v] {:domain/name (qualify-domain (normalize-domain v))})
                                    :unwrap        (comp unqualify-domain :domain/name)
                                    :auto-complete "off"
                                    :spell-check   false
                                    :postfix       [:span.text-sm.text-gray-500 ".sparkboard.com"]}
                                   props))))