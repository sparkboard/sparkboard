(ns sparkboard.server.validate
  (:require [clojure.string :as str]
            [malli.core :as m :refer [explain]]
            [malli.error :refer [humanize]]
            [sparkboard.schema :as s]))

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

(defn messages [schema value] (some-> schema (explain value) humanize flatten-messages))

(defn message-map [schema value]
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

(defn assert-valid
  ([schema value] (assert-valid 400 schema value))
  ([error-code schema value]
   (when-let [messages (seq (messages schema value))]
     (throw (ex-info (first messages) {:status error-code
                                       :messages messages
                                       :value value})))))

(comment
 (= (message-map [:map [:x :email]] {:x "foo"})
    {:x ["should be a valid email"]})
 (= (message-map :email " ")
    {:form/root ["should be a valid email"]})
 (= (message-map [:map
                  [:x
                   [:map
                    [:y :int]]]]
                 {:x {:y "foo"}})
    {:y ["should be an integer"]}))