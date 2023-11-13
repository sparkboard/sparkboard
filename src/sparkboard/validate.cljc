(ns sparkboard.validate
  (:refer-clojure :exclude [assert])
  (:require [clojure.string :as str]
            [malli.core :as m :refer [explain]]
            [malli.error :refer [humanize]]
            [malli.util :as mu]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.i18n :refer [tr]]
            [re-db.api :as db]
            [sparkboard.util :as u]))

(defn humanized [schema value]
  (some-> (explain schema value) humanize))


(defn leaves
  "Recursively walk a tree and return a sequence of [path, leaf] for all leaves,
   where a leaf is any non-map value."
  [tree]
  (let [walk (fn walk [path tree]
               (cond
                 (map? tree) (mapcat (fn [[k v]] (walk (conj path k) v)) tree)
                 :else [[path tree]]))]
    (walk [] tree)))

(defn segment-name [q]
  (if (qualified-ident? q)
    (str (namespace q) "/" (name q))
    (name q)))

(defn flatten-messages [humanized]
  (into []
        (mapcat (fn [[path messages]]
                  (map (fn [m]
                         (str m (when (seq path)
                                  (str ": " (str/join " > " (map segment-name path)))))) messages)))
        (leaves humanized)))

(def flat-messages (comp flatten-messages humanized))

(defn messages-by-path
  "Returns a map of {<path> [...messages]}"
  [schema value]
  (some-> (explain schema value)
          :errors
          (->> (reduce (fn [errors {:as error :keys [path]}]
                         (update errors
                                 (remove number? path)
                                 (fnil conj [])
                                 (malli.error/error-message error))) {})
               (into {}))))

(comment
  (messages-by-path [:map [:x :email]] {:x "foo"}))

(defn assert
  ([value schema] (assert value schema {:code 400}))
  ([value schema {:keys [code message]}]
   (when-let [messages (messages-by-path schema value)]
     (throw (ex-info "Validation failed"
                     {:response {:status (or code 400)
                                 :body   (if message
                                           {:error message}
                                           {:error                             "Validation failed"
                                            :inside-out.forms/messages-by-path messages})}
                      :value    value})))
   value))

(defn conform-and-validate
  "Conforms and validates an entity which may contain :entity/domain."
  [entity]
  {:pre [(:entity/id entity)]}
  (if (:entity/domain entity)
    (let [existing-domain (when-let [name (-> entity :entity/domain :domain/name)]
                            (db/entity [:domain/name name]))]
      (if (empty? existing-domain)
        entity                                              ;; upsert new domain entry
        (let [existing-id (-> existing-domain :entity/_domain first :entity/id)]
          (if (= existing-id (:entity/id entity))
            ;; no-op, domain is already pointing at this entity
            (dissoc entity :entity/domain)
            (throw (ex-info (tr :tr/domain-already-registered)
                            (into {} existing-domain)))))))
    entity))

#?(:clj
   (defn conform [m schema]
     (-> m
         (conform-and-validate)
         (assert (-> (mu/optional-keys schema)
                     (mu/assoc :entity/domain (mu/optional-keys :domain/as-map)))))))

#?(:clj
   (defn can-edit? [entity-id account-id]
     (let [entity-id  (dl/resolve-id entity-id)
           account-id (dl/resolve-id account-id)]
       (or (= entity-id account-id)                         ;; entity _is_ account
           (->> (dl/entity [:member/entity+account [entity-id account-id]])
                :member/roles
                (some #{:role/owner :role/admin :role/collaborate})
                boolean)))))

#?(:clj
   (defn assert-can-edit! [entity-id account-id]
     (when-not (can-edit? entity-id account-id)
       (throw (ex-info "Validation failed"
                       {:response {:status 400}})))))



(comment

  (messages-by-path [:map {:closed true} :entity/title]
                    {:entity/title nil})
  (= (messages-by-path [:map [:x :email]] {:x "foo"})
     {[:x] ["should be a valid email"]})
  (= (messages-by-path :email " ")
     {[] ["should be a valid email"]})
  (= (messages-by-path [:map
                        [:x
                         [:map
                          [:y :int]]]]
                       {:x {:y "foo"}})
     {[:x :y] ["should be an integer"]})
  (comment
    (messages-by-path [:string {:min 3}] "")
    )
  )
