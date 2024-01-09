(ns sb.util
  (:refer-clojure :exclude [ref])
  (:require [clojure.pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [promesa.core :as p]
            [re-db.hooks :as hooks]
            [re-db.memo :as memo]
            [re-db.reactive :as r])
  #?(:cljs (:require-macros sb.util)))

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
  (if (some-> s (str/starts-with? prefix))
    s
    (str prefix s)))

(defn find-first [coll pred]
  (reduce (fn [_ x] (if (pred x) (reduced x) _)) nil coll))

(defn prune
  "Removes nil values from a map recursively"
  [x]
  (if (sequential? x)
    (map prune x)
    (reduce-kv (fn [m k v]
                 (if (nil? v)
                   m
                   (if (map? v)
                     (assoc-seq m k (prune v))
                     (if (sequential? v)
                       (assoc-seq m k (map prune v))
                       (assoc m k v)))))
               {}
               x)))

(defn keep-changes
  "Removes nil values from a map, not recursive"
  [old new]
  (reduce-kv (fn [m k v]
               (if (= v (get old k))
                 (dissoc m k)
                 (assoc m k v))) {} new))

(defn select-as [m kmap]
  (reduce-kv (fn [out k as]
               (if (contains? m k)
                 (assoc out as (get m k))
                 out)) {} kmap))

(defn select-by [m pred]
  (reduce-kv (fn [out k v]
               (if (pred k)
                 (assoc out k v)
                 out)) {} m))

(defmacro p-when [test & body]
  `(p/let [result# ~test]
     (when result#
       ~@body)))

(defmacro template [x]
  (let [current (str *ns*)]
    (walk/postwalk (fn [x]
                     (if (and (symbol? x)
                              (= (namespace x) current))
                       (symbol (name x))
                       x)) x)))

(defn lift-key [m k]
  (merge (dissoc m k)
         (get m k)))

(defn dequote [id]
  (if (and (list? id) (= 'quote (first id)))
    (second id)
    id))


(defn memo-fn-var [query-var]
  (memo/fn-memo [& args]
    (r/reaction
      (let [f (hooks/use-deref query-var)]
        (apply f args)))))

#?(:clj
   (defn parse-defn-args [name args]
     (let [[doc args] (if (string? (first args))
                        [(first args) (rest args)]
                        [nil args])
           [options args] (if (map? (first args))
                            [(first args) (rest args)]
                            [nil args])
           [argv body] [(first args) (rest args)]]
       [name doc options argv body])))

(defn compare:desc
  "Compare two values in descending order."
  [a b]
  (compare b a))

(defmacro pprint [x]
  `(~'clojure.pprint/pprint ~x))

(defmacro some-or [& forms]
  (loop [forms (reverse forms)
         out   nil]
    (if (empty? forms)
      out
      (recur (rest forms)
             `(if-some [v# ~(first forms)]
                v#
                ~out)))))

(defmacro tapm [& vals]
  `(tap> (hash-map ~@(->> vals
                          (mapcat (fn [sym] `['~sym ~sym]))))))


(defn trim-prefix [s prefix]
  (if (some-> s (str/starts-with? prefix))
    (subs s (count prefix))
    s))

(defn some-str [s]
  (when-not (str/blank? s)
    s))

(defn truncate-string [s n]
  (if (> (count s) n)
    (str (subs s 0 (- n 3)) "...")
    s))

(defn wrap
  [[left right] s]
  (str left s right))