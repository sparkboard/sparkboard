(ns server.blocks
  (:require [cljs.pprint :as pp]
            [clojure.string :as str]))

(declare parse)

(defn wrap-string [k s]
  (if (string? s) [k s] s))

(defn update-some [m updaters]
  (reduce-kv (fn [m k update-fn]
               (if (contains? m k)
                 (update m k update-fn)
                 m)) m updaters))

(def type-string (comp #(str/replace % "-" "_") name))

(def schema
  "hiccup->block metadata. Supports:

  :child    -> key for single child
  :children -> key for list of children
  :props    -> map of default props
  :type     -> value for type (default: tag)"

  {"button" {:child :text
             :update-props {:text (partial wrap-string :plain_text)}}
   "plain_text" {:child :text
                 :props {:emoji true}}
   "md" {:child :text
         :type "mrkdwn"}
   "section" {:child :text
              :update-props {:text (partial wrap-string :md)}}
   "home" {:children :blocks}
   "modal" {:children :blocks
            :update-props {:title (partial wrap-string :plain_text)}}})

(defn apply-schema [tag props body]
  (let [tag-type (type-string tag)
        {:as tag-schema
         :keys [child children update-props]} (schema tag-type)
        children? (seq body)]
    (when (and (not (or child children)) children?)
      (throw (js/Error. (str "Tag does not support children: " tag ", props: " props))))
    (when child (assert (<= (count body) 1)))

    (merge (-> (:props tag-schema)
               (assoc :type (or (:type tag-schema) tag-type))
               (cond-> child (assoc child (first body)))
               (cond-> children (assoc children body))
               (merge props)
               (cond-> update-props (update-some update-props))))))

(defn has-props? [form]
  (map? (nth form 1 nil)))

(defn props? [x] (not (keyword-identical? ::not-found x)))

(defn parse-props [form]
  (if (map? (nth form 1 nil))
    (update form 1 parse)
    form))

(defn hiccup? [form] (and (vector? form) (keyword? (first form))))

(defn parse
  [form]
  (cond (hiccup? form)
        (let [tag (first form)
              props? (has-props? form)
              props (when props? (parse (nth form 1)))
              body (drop (if props? 2 1) form)]
          (parse (apply-schema tag props body)))
        (sequential? form) (reduce (fn [out child]
                                     (let [child (parse child)]
                                       ((if (sequential? child) into conj) out child))) [] form)
        (map? form) (reduce-kv (fn [m k v]
                                 (assoc m k (parse v))) {} form)
        :else form))

(comment
  ;; flatten sequences
  (= [1 2] (:text (parse [:section [(map identity [1 2])]])))
  )

(def to-js (comp clj->js parse))
(def to-json (comp js/JSON.stringify #(doto % pp/pprint) to-js))

(comment

  (parse [:modal {:title "Hi"}])

  (parse
    (list
      [:md "Hello"]
      [:button "What"]))

  (parse
    [:home
     [:section "Hello, _friend_."]])

  (parse
    [:section
     {:accessory [:conversations_select
                  {:placeholder {:type "plain_text",
                                 :text "Select a channel...",
                                 :emoji true},
                   :action_id "broadcast2:channel-select"
                   :filter {:include ["public" "private"]}}]}
     [:md "*Post responses to channel:*"]]))