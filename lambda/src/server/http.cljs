(ns server.http
  "HTTP verbs, via node-fetch"
  (:require ["node-fetch" :as node-fetch]
            [applied-science.js-interop :as j]
            [kitchen-async.promise :as p]
            [cljs.pprint :as pp]))

(defn assert-ok [^js/Response res]
  (when-not (.-ok res)
    (pp/pprint [:http/error (js->clj res)])
    (throw (ex-info "Invalid network request"
                    {:status (.-status res)})))
  res)

(defn fetch+ [url opts]
  (p/-> (node-fetch url (clj->js opts))
        (assert-ok)))

(defn fetch-json+ [url opts]
  (p/-> (fetch+ url (clj->js opts))
        (j/call :json)))
