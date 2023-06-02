(ns sparkboard.util
  (:require [clojure.string :as str]))

(defn guard [x f]
  (when (f x) x))

(defn assoc-some [m k v]
  (if (some? v)
    (assoc m k v)
    m))

(defn assoc-seq [m k v]
  (if (seq v)
    (assoc m k v)
    m))

(defn update-some [m updaters]
  (reduce-kv (fn [m k f]
               (let [v (get m k ::not-found)]
                 (if (= ::not-found v)
                   m
                   (assoc m k (f v))))) m updaters))

(defn update-some-paths [m & pvs]
  (reduce (fn [m [path f]]
            (if-some [v (get-in m path)]
              (assoc-in m path (f v))
              m))
          m
          (partition 2 pvs)))


(defn ensure-prefix [s prefix]
  (if (str/starts-with? s prefix)
    s
    (str prefix s)))

(defn some-str [s] (guard s (complement str/blank?)))

(defn find-first [coll pred]
  (reduce (fn [_ x] (if (pred x) (reduced x) _)) nil coll))

(defn prune
  "Removes nil values from a map recursively"
  [m]
  (reduce-kv (fn [m k v]
               (if (map? v)
                 (assoc-seq m k (prune v))
                 (if (or (nil? v) (and (coll? v) (empty? v)))
                   m
                   (assoc m k v)))) {} m))

(defn keep-changes
  "Removes nil values from a map recursively"
  [old new]
  (reduce-kv (fn [m k v]
               (let [oldv (get old k)]
                 (if (map? v)
                   (assoc-seq m k (keep-changes oldv v))
                   (if (= v (get old k))
                     (dissoc m k)
                     (assoc m k v))))) {} new))

(comment
  (prune {:x nil
          :y {:z nil}})
  (keep-changes {:entity/domain {:domain/name nil}}
                {:entity/domain {:domain/name "foo"}}))
  
(defn select-as [m kmap]
  (reduce-kv (fn [out k as]
               (if (contains? m k)
                 (assoc out as (get m k))
                 out)) {} kmap))