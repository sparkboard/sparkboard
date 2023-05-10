(ns sparkboard.impl.schema
  (:refer-clojure :exclude [ref])
  (:require [malli.generator :as mg]
            [re-db.schema :as s]))

(defn gen [s] (take 3 (repeatedly #(mg/generate s))))

(def s- :malli/schema)

(defn lookup-ref [ks]
  [:tuple (into [:enum] ks) :string])

(defn ref
  "returns a schema entry for a ref (one or many)"
  ([cardinality] (case cardinality :one (merge s/ref
                                               s/one
                                               {s- [:or
                                                    :int
                                                    [:tuple :qualified-keyword :string]]})
                                   :many (merge s/ref
                                                s/many
                                                {s- [:or
                                                     :int
                                                     [:sequential [:tuple :qualified-keyword :string]]]})))
  ([cardinality ks]
   (let [nested? (= 1 (count ks))
         leaf-type [:or
                    :int
                    (if nested?
                      [:multi {:dispatch 'map?}
                       [true [:ref (keyword (namespace (first ks)) "as-map")]]
                       [false (lookup-ref ks)]]
                      (lookup-ref ks))]]
     (case cardinality :one (merge s/ref
                                   s/one
                                   {s- leaf-type}
                                   (when nested? {:db/nested-ref? true}))
                       :many (merge s/ref
                                    s/many
                                    {s- [:sequential leaf-type]}
                                    (when nested? {:db/nested-ref? true}))))))

(def unique-string-id (merge s/unique-id
                             s/string
                             {s- :string}))

(defn ? [k]
  (if (keyword? k)
    [k {:optional true}]
    (do (assert (vector? k))
        (if (map? (second k))
          (update k 1 assoc :optional true)
          (into [(first k) {:optional true}] (rest k))))))

(defn update-attrs [schema f & args]
  (if (ident? schema)
    [schema (apply f {} args)]
    (do (assert (vector? schema) (str "Not a vector: " schema))
        (if (map? (second schema))
          (update schema 1 #(apply f % args))
          (into [(first schema) (apply f {} args)] (rest schema))))))

(defn infer-db-type [m]
  (let [inferred-type (when (and (s- m) (not (:db/valueType m)))
                        (let [base-mappings {:string s/string
                                             :boolean s/boolean
                                             :keyword s/keyword
                                             :http/url s/string
                                             :html/color s/string
                                             :int s/long #_s/bigint
                                             'inst? s/instant}
                              known-bases (set (keys base-mappings))
                              malli-type (as-> (s- m) t
                                               (cond-> t (vector? t) first))
                              malli-base (or (known-bases malli-type)
                                             (when (vector? malli-type)
                                               (or (when (and (= :db.cardinality/many (:db/cardinality m))
                                                              (#{:sequential :vector :set} (first malli-type)))
                                                     (known-bases (second malli-type)))
                                                   (when (#{:enum} (first malli-type))
                                                     (let [x (second malli-type)]
                                                       (cond (keyword? x) :keyword
                                                             (string? x) :string)))
                                                   (when (#{:re} (first malli-type))
                                                     :string))))]
                          (base-mappings malli-base)))]
    (merge inferred-type m)))