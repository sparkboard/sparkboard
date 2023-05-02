(ns sparkboard.server.validate
  (:refer-clojure :exclude [assert])
  (:require [clojure.string :as str]
            [malli.core :as m :refer [explain]]
            [malli.error :refer [humanize]]
            [sparkboard.schema :as s]))

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

(defn assert
  ([value schema] (assert value schema {:code 400}))
  ([value schema {:keys [code message]}]
   (when-let [messages (seq (flat-messages schema value))]
     (throw (ex-info (or message (first messages))
                     {:status (or code 400)
                      :messages messages
                      :value value})))))

(defn leaf-map [schema value]
  ;; returns map like
  '{:root [...]
    :leaf-key [...]}
  (some-> schema
          (explain value)
          humanize
          leaves
          (->> (into {}))
          (update-keys (fn [k]
                         (if (seq k)
                           (last k)
                           :form/root))))
  )


(comment
 (= (leaf-map [:map [:x :email]] {:x "foo"})
    {:x ["should be a valid email"]})
 (= (leaf-map :email " ")
    {:form/root ["should be a valid email"]})
 (= (leaf-map [:map
               [:x
                [:map
                 [:y :int]]]]
              {:x {:y "foo"}})
    {:y ["should be an integer"]}))