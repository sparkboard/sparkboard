(ns org.sparkboard.server.slack.hiccup
  (:require [clojure.string :as str]
            [org.sparkboard.js-convert :refer [clj->json kw->js]]
            [org.sparkboard.transit :as transit]
            [taoensso.timbre :as log]))

(def schema
  "hiccup<->block metadata. Supports:

  :child (kw)     -> puts single child element at this key
  :children (kw)  -> puts list of children at this key
  :defaults (map) -> map of default props
  :type (string)  -> uses this as the block type (instead of the tag's name)
  :strings (map)  -> map of {key, tag}, will wrap strings at key in the provided text tag"

  {"button" {:child :text
             :strings {:text :plain_text}}
   "plain_text" {:children :text
                 :defaults {:emoji true}
                 :update-keys {:text str/join}}
   "md" {:children :text
         :type "mrkdwn"
         :update-keys {:text str/join}}
   "section" {:child :text
              :strings {:text :md}}
   "home" {:children :blocks}
   "modal" {:children :blocks
            :strings {:title :plain_text
                      :submit :plain_text
                      :close :plain_text}
            :update-keys {:private_metadata transit/write}}
   "actions" {:children :elements}
   "input" {:strings {:label :plain_text
                      :hint :plain_text}
            :child :element}
   "context" {:children :elements}
   "users_select" {:strings {:placeholder :plain_text}}
   "plain_text_input" {:strings {:placeholder :plain_text}}
   "multi_static_select" {:strings {:placeholder :plain_text}}
   "multi_users_select" {:strings {:placeholder :plain_text}}
   "multi_external_select" {:strings {:placeholder :plain_text}}
   "multi_conversations_select" {:strings {:placeholder :plain_text}}
   "multi_channels_select" {:strings {:placeholder :plain_text}}})


(declare ->blocks)

(def aliases
  (reduce-kv (fn [m k {:keys [type]}]
               (cond-> m
                 type (assoc type k))) {} schema))

(defn type-string [x]
  (let [x (str/replace (name x) "-" "_")]
    (or (aliases x) x)))

(defn wrap-strings [props wrappers]
  (reduce-kv (fn [props k wrap-with]
               (let [v (get props k)]
                 (if (string? v)
                   (assoc props k [wrap-with v])
                   props))) props wrappers))

(defn update-some [m updaters]
  (reduce-kv (fn [m k f]
               (if (contains? m k)
                 (update m k f)
                 m)) m updaters))

(defn assoc-some [m k v]
  (if v
    (assoc m k v)
    m))

(defn- all-options [{:keys [options option-groups]}]
  (concat options (->> option-groups (mapcat :options))))

(defn assoc-option
  [m as-key value]
  (assoc-some m as-key (first (filter #(= (:value %) value) (all-options m)))))

(defn assoc-options
  [m as-key values]
  (assoc-some m as-key (seq (filter #(contains? values (:value %)) (all-options m)))))

(defn set-value [m tag value]
  (if (nil? value)
    m
    (case tag
      "checkboxes" (assoc-options m :initial_options value)
      "radio_buttons" (assoc-option m :initial_option value)
      "users_select" (assoc-some m :initial_user value)
      "datepicker" (assoc-some m :initial_date value)
      "multi_channels_select" (assoc-some m :initial_channels value)
      "multi_conversations_select" (assoc-some m :initial_conversations value)
      "multi_static_select" (assoc-options m :initial_options value)
      "multi_users_select" (assoc-some m :initial_users value)
      "plain_text_input" (assoc-some m :initial_value value))))

(def view-value-key :org.sparkboard.slack.view/value)

(defn normalize-value [m]
  (let [v (get m view-value-key ::not-found)]
    (if (= v ::not-found)
      m
      (-> m
          (set-value (:type m) v)
          (dissoc view-value-key)))))

(defn apply-schema [tag props body]
  (let [type-str (type-string tag)
        {:as tag-schema
         :keys [child children strings update-keys]} (schema type-str)
        children? (seq body)
        error (cond (and children? (not child) (not children))
                    (str "Tag does not support children: " tag ", props: " props)
                    (and child (> (count body) 1))
                    "Tag supports only a single child")]
    (when error
      (throw (ex-info error {:tag tag :body body})))

    (merge (-> (:defaults tag-schema)
               (assoc :type (or (:type tag-schema) type-str))
               (cond-> (and children? child) (assoc child (first body)))
               (cond-> (and children? children) (assoc children body))
               (merge props)
               (cond-> strings (wrap-strings strings)
                       update-keys (update-some update-keys))
               (normalize-value)))))

(defn has-props? [form]
  (map? (nth form 1 nil)))

(defn hiccup? [form] (and (vector? form) (keyword? (first form))))

(defn kw->underscore [k]
  (str/replace (name k) "-" "_"))

(defn ->blocks
  "Converts hiccup to blocks"
  [form]
  (cond (hiccup? form)
        (let [tag (first form)
              props? (has-props? form)
              props (when props? (nth form 1))
              body (drop (if props? 2 1) form)]
          (->blocks (apply-schema tag props body)))
        (sequential? form) (reduce (fn [out child]
                                     (let [child (->blocks child)]
                                       (cond (nil? child) out
                                             (sequential? child) (into out child)
                                             :else (conj out child)))) [] form)
        (map? form) (reduce-kv (fn [m k v]
                                 (assoc m (kw->underscore k) (->blocks v))) {} form)
        (keyword? form) (kw->js form)
        (symbol? form) (name form)
        :else form))

(defn remove-redundant-defaults [defaults props]
  (reduce-kv
    (fn [props k v]
      (cond-> props
        (= v (get defaults k))
        (dissoc k))) props defaults))

(defn unwrap-strings [strings props]
  (reduce-kv
    (fn [props k wrap-tag]
      (let [[tag maybe-string] (get props k)
            unwrap? (and (string? maybe-string)
                         (= (type-string tag) (type-string wrap-tag)))]
        (if unwrap?
          (assoc props k maybe-string)
          props)))
    props strings))

(defn ->hiccup
  "Converts blocks to hiccup"
  [form]
  (cond (and (map? form) (:type form))
        (let [type-name (type-string (:type form))
              type-key (keyword type-name)
              {:keys [child children
                      defaults strings]} (schema type-name)
              props (->> (dissoc form :type)
                         (reduce-kv (fn [props k v] (assoc props k (->hiccup v))) {})
                         (remove-redundant-defaults defaults)
                         (unwrap-strings strings))
              child-elements (some->> (seq (cond child (some-> (get props child) (vector))
                                                 children (get props children)))
                                      (map ->hiccup))
              props (-> props
                        (dissoc child children)
                        (not-empty))]
          (cond-> [type-key]
            props (conj props)
            child-elements (into child-elements)))
        (sequential? form)
        (into (empty form) (map ->hiccup) form)
        :else form))

(defn ->blocks-json [hiccup]
  (log/trace :hiccup hiccup)
  (let [blocks (->blocks hiccup)]
    (log/trace :blocks blocks)
    (clj->json blocks)))

(comment

  (= (->hiccup
       {:type "mrkdwn"
        :text "Hello"})
     [:md "Hello"])

  (= (->hiccup
       {:type "button"
        :text {:emoji true, :type "plain_text", :text "What"}})
     [:button "What"])

  (= (->blocks
       [:section {:blocks (list
                            (list
                              (list 1 2)))}])
     ;; nested lists are flattened
     {:type "section"
      :blocks [1 2]})


  (defn round-trip? [x]
    (let [blocks (->blocks x)
          hiccup (->hiccup blocks)]
      (if (= x hiccup)
        true
        {:x x
         :blocks blocks
         :hiccup hiccup})))

  (round-trip?
    [:modal {:title "Hi"}])

  (round-trip?
    (list
      [:md "Hello"]
      [:button "What"]))

  (round-trip?
    [:home
     [:section "Hello, _friend_."]])

  (round-trip?
    [:section
     {:accessory [:conversations_select
                  {:placeholder
                   [:plain_text "Select a channel..."],
                   :action_id "broadcast2:channel-select"
                   :filter {:include ["public" "private"]}}]}
     "*Post responses to channel:*"]))

