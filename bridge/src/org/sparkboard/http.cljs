(ns org.sparkboard.http
  "HTTP verbs, via node-fetch"
  (:require ["isomorphic-unfetch" :as fetch]
            [applied-science.js-interop :as j]
            [cljs.pprint :as pp]
            [kitchen-async.promise :as p]
            [org.sparkboard.js-convert :refer [->clj]]))

(defn assert-ok [^js/Response res]
  (when-not (.-ok res)
    (pp/pprint [:http/error (js->clj res)])
    (throw (ex-info "Invalid network request"
                    (j/select-keys res [:status :statusText :body]))))
  res)

(defn fetch+ [url opts]
  (p/-> (fetch url (clj->js opts))
        (assert-ok)))

(defn fetch-json+ [url opts]
  (p/-> (fetch+ url opts)
        (j/call :json)))

(defn fetch-clj+ [url opts]
  (p/-> (fetch+ url opts)
        (j/call :json)
        ->clj))
