(ns org.sparkboard.http
  "HTTP verbs, via node-fetch"
  #?(:cljs
     (:require
       ["isomorphic-unfetch" :as fetch]
       [applied-science.js-interop :as j]
       [clojure.pprint :as pp]
       [org.sparkboard.js-convert :refer [->clj clj->json json->clj ->js]]
       [org.sparkboard.promise :as p]
       [clojure.string :as str]
       [org.sparkboard.transit :as transit])
     :clj
     (:require
       [org.sparkboard.js-convert :refer [->clj clj->json json->clj ->js]]
       [org.sparkboard.promise :as p]
       [org.sparkboard.transit :as transit]
       [clj-http.client :as client]
       [clojure.string :as str]))
#?(:clj (:import (java.util Base64))))

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
     :clj (-> res :headers (get "Content-Type"))))

(defn json-body [res]
  ;; return json as Clojure body
  #?(:cljs (p/-> res (j/call :text) json->clj)
     :clj  (-> res :body json->clj)))

(defn transit-body [res]
  #?(:cljs (p/-> res (j/call :text) transit/read)
     :clj  (-> res :body transit/read)))

(defn formatted-body [res]
  (let [type (content-type res)]
    (cond (str/starts-with? type "application/json") (json-body res)
          (str/starts-with? type "application/transit+json") (transit-body res)
          :else res)))

(defn http-req [url {:as opts :keys [body method format]
                     :or {format :json}}]
  (let [[body opts] (if body
                      [(case format :json (clj->json body)
                                    :transit+json (transit/write body)
                                    body)
                       (-> opts
                           (dissoc :format)
                           (assoc-in [:headers "Content-Type"] (str "application/" (name format))))]
                      [body opts])]
    (p/let [response #?(:cljs
                        (p/-> (fetch url (-> opts
                                             (cond-> body (assoc :body body))
                                             (->js)))
                              (assert-ok))
                        :clj
                        (case method
                          "GET" (client/get url opts)
                          "PUT" (client/put url opts)
                          "POST" (client/post url opts)
                          "PATCH" (client/patch url opts)))]
      (formatted-body response))))

(defn partial-opts [http-fn extra-opts]
  (fn [path & [opts]]
    (http-fn path (merge extra-opts opts))))

(def get+ (partial-opts http-req {:method "GET"}))
(def put+ (partial-opts http-req {:method "PUT"}))
(def post+ (partial-opts http-req {:method "POST"}))
(def patch+ (partial-opts http-req {:method "PATCH"}))

(defn decode-base64 [s]
  #?(:cljs (.toString (.from js/Buffer s "base64"))
     :clj  (String. (.decode (Base64/getDecoder) s))))

(defn encode-base64 [s]
  #?(:cljs (-> (new js/Buffer s)
               (.toString "base64"))
     :clj (.encode (Base64/getEncoder) (.getBytes s))))
