(ns org.sparkboard.util)

(defn guard [x f]
  (when (f x) x))

(defn assoc-some [m k v]
  (if (some? v)
    (assoc m k v)
    m))

(defn update-some [m updaters]
  (reduce-kv (fn [m k f]
               (let [v (get m k ::not-found)]
                 (if #?(:clj (identical? v ::not-found) :cljs (keyword-identical? v ::not-found))
                   m
                   (assoc m k (f v))))) m updaters))

