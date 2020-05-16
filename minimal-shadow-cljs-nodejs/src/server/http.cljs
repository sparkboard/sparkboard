(ns server.http
  "HTTP verbs, via node-fetch"
  (:require ["node-fetch" :as node-fetch]
            [applied-science.js-interop :as j]
            [kitchen-async.promise :as p]))

(defn assert-ok [^js/Response res]
  (when-not (.-ok res)
    (throw (ex-info "Invalid network request"
                    {:status (.-status res)})))
  res)

(defn fetch+ [url opts]
  (p/-> (node-fetch url opts)
        (assert-ok)))

(defn fetch-json+ [url opts]
  (p/-> (fetch+ url opts)
        (j/call :json)))
