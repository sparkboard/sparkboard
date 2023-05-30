(ns sparkboard.entities.domain
  (:require [clojure.string :as str]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]))

(defn qualify-domain [domain]
  (when domain
    (if (str/includes? domain ".")
      domain
      (str domain ".sparkboard.com"))))

(defn availability [req {{:keys [domain]} :query-params}]
  (let [domain (qualify-domain domain)]
    {:body {:available?
            (and (re-matches #"^[a-z0-9-.]+$" domain)
                 (nil? (db/get [:domain/name domain] :domain/name)))
            :domain domain}}))


(defn domain-valid-chars [v _]
  (when (and v (not (re-matches #"^[a-z0-9-]+$" v)))
    (forms/message :invalid
                   (tr :tr/invalid-domain)
                   {:visibility :always})))

#?(:cljs
   (defn domain-availability-validator []
     (-> (fn [v _]
           (when (>= (count v) 3)
             (p/let [res (routes/GET :domain/availability :query-params {:domain v})]
               (if (:available? res)
                 (forms/message :info
                                [:span.text-green-500.font-bold (tr :tr/available)])
                 (forms/message :invalid
                                (tr :tr/not-available)
                                {:visibility :always})))))
         (forms/debounce 1000))))