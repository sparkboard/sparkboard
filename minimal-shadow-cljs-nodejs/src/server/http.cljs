(ns server.http
  "HTTP verbs, via node-fetch"
  (:require ["node-fetch" :as node-fetch]))

(defn fetch+ [decode-fn url opts]
  (-> (node-fetch url opts)
      (.then (fn [^js/Response res] (if (.-ok res)
                                     (decode-fn res)
                                     (throw (ex-info "Invalid network request"
                                                     {:status (.-status res)})))))))

(defn decode-json [^js/Response resp] (.json resp))

(def ^js/Promise fetch-json+ (partial fetch+ decode-json))
