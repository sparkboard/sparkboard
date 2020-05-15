(ns server.http
  "HTTP verbs, via node-fetch"
  (:require ["node-fetch" :as nf]))

;; `node-fetch` is in `require` for static analysis by shadow; we
;; `js/require` it here to get its function in a callable form
(def node-fetch (js/require "node-fetch"))

(defn fetch+ [decode-fn url opts]
  (-> (node-fetch url opts)
      (.then (fn [^js/Response res] (if (.-ok res)
                                     (decode-fn res)
                                     (throw (ex-info "Invalid network request"
                                                     {:status (.-status res)})))))))

(defn decode-json [^js/Response resp] (.json resp))

(def ^js/Promise fetch-json+ (partial fetch+ decode-json))
