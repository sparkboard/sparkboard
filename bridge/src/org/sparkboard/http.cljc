(ns org.sparkboard.http
  "HTTP verbs, via node-fetch"
  #?(:cljs
     (:require
       ["isomorphic-unfetch" :as fetch]
       [applied-science.js-interop :as j]
       [clojure.pprint :as pp]
       [org.sparkboard.js-convert :refer [->clj clj->json json->clj ->js]]
       [org.sparkboard.promise :as p]
       [clojure.string :as str])
     :clj
     (:require
       [org.sparkboard.js-convert :refer [->clj clj->json json->clj ->js]]
       [org.sparkboard.promise :as p]
       [clj-http.client :as client]
       [clojure.string :as str])))

#?(:cljs
   (defn assert-ok [^js/Response res]
     (when-not (.-ok res)
       (pp/pprint [:http/error (js->clj res)])
       (throw (ex-info "Invalid network request"
                       (j/select-keys res [:status :statusText :body]))))
     res))

(defn content-type [res]
  ;; TODO - use cljs-bean for stuff like this?
  #?(:cljs (-> (j/get res :headers) (j/call :get "Content-Type"))
     :clj (-> res :headers "Content-Type")))

(defn json? [res]
  (-> (content-type res)
      (str/starts-with? "application/json")))

(defn json->clj-body [res]
  ;; return json as Clojure body
  (if (json? res)
    #?(:cljs (p/-> res (j/call :text) json->clj)
       :clj (-> res :body json->clj))
    res))

(defn http-req [url {:as opts :keys [body method]}]
  (p/let [response #?(:cljs
                      (p/-> (fetch url (-> opts
                                           (cond-> body (update :body clj->json))
                                           (->js)))
                            (assert-ok))
                      :clj
                      (case method
                        "GET" (client/get url opts)
                        "PUT" (client/put url opts)
                        "POST" (client/post url opts)
                        "PATCH" (client/patch url opts)))]
    (json->clj-body response)))

(defn partial-opts [http-fn extra-opts]
  (fn [path & [opts]]
    (http-fn path (merge extra-opts opts))))

(def get+ (partial-opts http-req {:method "GET"}))
(def put+ (partial-opts http-req {:method "PUT"}))
(def post+ (partial-opts http-req {:method "POST"}))
(def patch+ (partial-opts http-req {:method "PATCH"}))
