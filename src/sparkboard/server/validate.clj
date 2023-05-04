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
                     {:status (or code 400)
                      :body {:inside-out.forms/messages-by-path messages}
                      :value value})))))

(comment
 (messages-by-path [:map {:closed true} :org/title]
                   {:org/title nil})
 (= (messages-by-path [:map [:x :email]] {:x "foo"})
    {[:x] ["should be a valid email"]})
 (= (messages-by-path :email " ")
    {[] ["should be a valid email"]})
 (= (messages-by-path [:map
               [:x
                [:map
                 [:y :int]]]]
                      {:x {:y "foo"}})
    {[:x :y] ["should be an integer"]}))