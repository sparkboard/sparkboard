(ns server.blocks
  (:require [cljs.pprint :as pp]))

(declare parse)

(defn wrap-string
  ([k]
   (fn [s] (wrap-string k s)))
  ([k s]
   (if (string? s) [k s] s)))

(defn update-some [m updaters]
  (reduce-kv (fn [m k update-fn]
               (if (contains? m k)
                 (update m k update-fn)
                 m)) m updaters))

(def resolvers
  {:button (fn [tag props [text]]
             (-> {:type "button"}
                 (merge (when text {:text text}) props)
                 (update-some {:text (wrap-string :plain_text)})))
   :plain_text (fn [tag props [text]]
                 (merge {:type "plain_text"
                         :emoji true
                         :text text} props))
   :md (fn [tag props [text]]
         (merge {:type "mrkdwn"
                 :text text} props))
   :section (fn [tag props [text]]
              (-> {:type "section"}
                  (merge (when text {:text text}) props)
                  (update-some {:text (wrap-string :md)})))
   :home (fn [tag props children]
           (merge {:type "home"
                   :blocks children} props))
   ::default (fn [tag props children]
               (assert (empty? children) (str "Children are ignored for tag: " tag ", props: " props))
               (merge {:type (name tag)} props))
   :modal (fn [tag props body]
            (-> (merge {:type "modal"
                        :blocks body} props)
                (update-some {:title (wrap-string :plain_text)})))})

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
              resolver (or (resolvers tag) (resolvers ::default))
              props (when props? (parse (nth form 1)))
              body (drop (if props? 2 1) form)]
          (parse (resolver tag props body)))
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

  (parse [:home
          [:section [:md "Hello, _friend_."]]])

  (parse
    [:section
     {:accessory [:conversations_select
                  {:placeholder {:type "plain_text",
                                 :text "Select a channel...",
                                 :emoji true},
                   :action_id "broadcast2:channel-select"
                   :filter {:include ["public" "private"]}}]}
     [:md "*Post responses to channel:*"]]))