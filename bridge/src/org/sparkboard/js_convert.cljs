(ns org.sparkboard.js-convert
  (:refer-clojure :exclude [clj->js])
  (:require [cljs.core :as core]))

;; js<>clj conversion interop with namespaced keys retained

(defn kw->js [k]
  (if-some [ns (namespace k)]
    (str ns "/" (name k))
    (name k)))

(defn ->js [x]
  (core/clj->js x :keyword-fn kw->js))

(defn ->clj [x]
  (core/js->clj x :keywordize-keys true))

(defn json->clj [json]
  (-> (.parse js/JSON json)
      (->clj)))

(defn clj->json [x]
  (.stringify js/JSON (->js x)))

(comment
  (->clj (->js {:slack/id 1})))