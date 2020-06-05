(ns org.sparkboard.js-convert
  #?(:clj (:require
            [jsonista.core :as json]
            [clojure.walk :as walk])))

;; js<>clj conversion interop with namespaced keys retained

(defn kw->js [k]
  (if-some [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

#?(:clj
   (defn stringify-keys [x]
     (let [f (fn [[k v]] (if (keyword? k) [(cond->> (name k)
                                                    (namespace k) (str (namespace k) "/")) v] [k v]))]
       ;; only apply to maps
       (walk/postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) x))))

(defn ->js [x]
  #?(:cljs (clj->js x :keyword-fn kw->js)
     :clj  (stringify-keys x)))

(defn ->clj [x]
  #?(:cljs (js->clj x :keywordize-keys true)
     :clj  x))

#?(:clj
   (def jsonista-mapper (json/object-mapper
                          {:encode-key-fn kw->js
                           :decode-key-fn keyword})))

(defn json->clj [x]
  #?(:cljs
     (-> (.parse js/JSON x)
         (->clj))
     :clj
     (json/read-value x jsonista-mapper)))

(defn clj->json [x]
  #?(:cljs
     (.stringify js/JSON (->js x))
     :clj
     (json/write-value-as-string x jsonista-mapper)))

(comment
  (->clj (->js {:slack/id 1})))

(defn update-json [json f & args]
  (clj->json (apply f (json->clj json) args)))
