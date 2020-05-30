(ns org.sparkboard.http
  "HTTP verbs for browser, node.js and jvm"
  (:require [org.sparkboard.js-convert :refer [->clj clj->json json->clj ->js]]
            [org.sparkboard.promise :as p]
            [clojure.string :as str]
            [org.sparkboard.transit :as transit]
            [lambdaisland.uri :as uri]
            [taoensso.timbre :as log]
            #?@(:cljs
                [["isomorphic-unfetch" :as fetch]
                 [applied-science.js-interop :as j]]
                :clj
                [[clj-http.client :as client]]))
  #?(:clj (:import (java.util Base64))))

#?(:cljs
   (defn assert-ok [^js/Response res]
     (when-not (.-ok res)
       (prn [:http/error (js->clj res)])
       (throw (ex-info "Invalid network request"
                       (j/select-keys res [:status :statusText :body]))))
     res))

(defn content-type [res]
  ;; TODO - use cljs-bean for stuff like this?
  #?(:cljs (-> (j/get res :headers) (j/call :get "Content-Type"))
     :clj  (-> res :headers (get "Content-Type"))))

(defn json-body [res]
  ;; return json as Clojure body
  #?(:cljs (p/let [text (j/call res :text)]
             (when-not (str/blank? text)
               (json->clj text)))
     :clj  (-> res :body json->clj)))

(defn transit-body [res]
  #?(:cljs (p/-> res (j/call :text) transit/read)
     :clj  (-> res :body transit/read)))

(defn format-response [res]
  (let [type (content-type res)]
    (cond (str/starts-with? type "application/json") (json-body res)
          (str/starts-with? type "application/transit+json") (transit-body res)
          :else res)))

(defn format-req-body [{:as opts
                        :keys [body body/content-type]
                        :or {content-type :json}}]
  (-> opts
      (assoc-in [:headers "Content-Type"] (str "application/" (name content-type)))
      (assoc :body (case content-type :json (clj->json body)
                                      :transit+json (transit/write body)
                                      body))
      (dissoc :body/content-type)))

(defn format-req-token [{:as opts :keys [auth/token]}]
  (if token
    (-> opts
        (-> (assoc-in [:headers "Authorization"] (str "Bearer: " token))
            (dissoc :auth/token)))
    opts))

(defn http-req [url {:as opts :keys [query body auth/token method]}]
  (let [opts (cond-> opts
               token (assoc-in [:headers "Authorization"] (str "Bearer: " token))
               body (format-req-body)
               true (dissoc :query :auth/token))
        url (cond-> url query (str "?" (uri/map->query-string query)))]
    (p/let [response #?(:cljs
                        (p/-> (fetch url (->js opts))
                              (assert-ok))
                        :clj
                        (case method
                          "GET" (client/get url opts)
                          "PUT" (client/put url opts)
                          "POST" (client/post url opts)
                          "PATCH" (client/patch url opts)))]
      (format-response response))))

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
     :clj  (.encode (Base64/getEncoder) (.getBytes s))))
