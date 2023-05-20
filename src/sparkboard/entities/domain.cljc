(ns sparkboard.entities.domain
  (:require [inside-out.forms :as forms]
            [sparkboard.i18n :refer [tr]]
            [promesa.core :as p]
            [clojure.string :as str]
            [sparkboard.routes :as routes]
            [sparkboard.server.query :as query]
            [re-db.api :as db]))

(defn qualify-domain [domain]
  (if (str/includes? domain ".")
    domain
    (str domain ".sparkboard.com")))

(query/static availability
  [_ {:keys [domain]}]
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
             (p/let [res (routes/GET :domain/availability :query {:domain v})]
               (if (:available? res)
                 (forms/message :info
                                [:span.text-green-500.font-bold (tr :tr/available)])
                 (forms/message :invalid
                                (tr :tr/not-available)
                                {:visibility :always})))))
         (forms/debounce 1000))))