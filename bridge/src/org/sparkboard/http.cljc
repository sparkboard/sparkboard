(ns org.sparkboard.http
  "HTTP verbs, via node-fetch"
  #?(:cljs
     (:require
       ["isomorphic-unfetch" :as fetch]
       [kitchen-async.promise :as p]
       [applied-science.js-interop :as j]
       [org.sparkboard.js-convert :refer [->clj clj->json json->clj ->js]]
       [clojure.pprint :as pp]
       [org.sparkboard.js-convert :as jc])
     :clj
     (:require
       [clojure.pprint :as pp]
       [clj-http.client :as client])))

#?(:cljs
   (defn assert-ok [^js/Response res]
     (when-not (.-ok res)
       (pp/pprint [:http/error (js->clj res)])
       (throw (ex-info "Invalid network request"
                       (j/select-keys res [:status :statusText :body]))))
     res))

(defn http-req [url {:as opts :keys [body method]}]
  #?(:cljs
     (p/-> (fetch url (-> opts
                          (cond-> body (update :body clj->json))
                          (->js)))
           (assert-ok)
           (j/call :text)
           json->clj)
     :clj
     (case method
       "GET" (client/get url opts)
       "PUT" (client/put url opts)
       "POST"  (client/post url opts)
       "PATCH" (client/patch url opts))))

(defn partial-opts [http-fn extra-opts]
  (fn [path & [opts]]
    (http-fn path (merge extra-opts opts))))

(def get+ (partial-opts http-req {:method "GET"}))
(def put+ (partial-opts http-req {:method "PUT"}))
(def post+ (partial-opts http-req {:method "POST"}))
(def patch+ (partial-opts http-req {:method "PATCH"}))
