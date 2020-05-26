(ns org.sparkboard.js-convert
  (:require #?(:clj [jsonista.core :as json])))

;; js<>clj conversion interop with namespaced keys retained

(defn kw->js [k]
  (if-some [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

#?(:cljs
   (defn ->js [x]
     (clj->js x :keyword-fn kw->js)))

#?(:cljs
   (defn ->clj [x]
     (js->clj x :keywordize-keys true)))

#?(:clj
   (def jsonista-mapper {:encode-key-fn kw->js
                         :decode-key-fn keyword}))

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
