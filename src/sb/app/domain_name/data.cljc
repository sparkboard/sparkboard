(ns sb.app.domain-name.data
  (:require [clojure.string :as str]
            [inside-out.forms :as forms]
            [re-db.api :as db]
            [sb.authorize :as az]
            [sb.i18n :refer [tr]]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]))

(sch/register!
  {:domain-name/redirect-url {s- :http/url}
   :domain-name/name         (merge {:doc "A complete domain name, eg a.b.com"}
                                    sch/unique-id-str)
   :domain/owner             (sch/ref :one)
   :entity/domain-name       (merge (sch/ref :one :domain-name/as-map)
                                    sch/unique-value)
   :entity/_domain-name      {s- [:map {:closed true} :entity/id]}
   :domain-name/as-map       (merge (sch/ref :one)
                                    {s- [:map {:closed true}
                                         :domain-name/name
                                         (? :entity/_domain-name)
                                         (? :domain-name/redirect-url)
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
          (empty?
            (:entity/_domain-name (db/entity [:domain-name/name domain]))))
     :domain domain}))

(defn domain-valid-string [v _]
  (when-let [v (unqualify-domain (:domain-name/name v))]
    (when (< (count v) 3)
      (tr :tr/too-short))
    (when-not (re-matches #"^[a-z0-9-]+$" v)
      (forms/message :invalid
                     (tr :tr/invalid-domain)
                     {:visibility :always}))))