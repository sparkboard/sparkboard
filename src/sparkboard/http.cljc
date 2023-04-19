(ns sparkboard.http
  "HTTP verbs for browser, node.js and jvm"
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [lambdaisland.uri :as uri]
            [sparkboard.js-convert :refer [clj->json json->clj ->js]]
            [sparkboard.promise :as p]
            [sparkboard.transit :as transit]
            #?(:cljs ["isomorphic-unfetch" :as fetch]
               :clj  [clj-http.client :as client])))

#?(:cljs
   (defn assert-ok [^js/Response res url]
     (when-not (.-ok res)
       (prn [:http/error (js->clj res)])
       (throw (ex-info "Invalid network request"
                       (-> res
                           (j/select-keys [:status :statusText :body])
                           (j/assoc! :url url)))))
     res))

(defn content-type [res]
  ;; TODO - use cljs-bean for stuff like this?
  #?(:cljs (j/call-in res [:headers :get] "Content-Type")
     :clj  (get-in res [:headers "Content-Type"])))

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

(defn request [url {:as opts :keys [query body auth/token method response-fn]}]
  (let [opts (cond-> opts
                     token (assoc-in [:headers "Authorization"] (str "Bearer: " token))
                     body (format-req-body)
                     true (dissoc :query :auth/token :response-fn))
        url (cond-> url query (str "?" (uri/map->query-string query)))]
    (p/let [response #?(:cljs
                        (p/-> (fetch url (->js opts))
                              ((if response-fn
                                 response-fn
                                 assert-ok) url))
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

(def get+ (partial-opts request {:method "GET"}))
(def put+ (partial-opts request {:method "PUT"}))
(def post+ (partial-opts request {:method "POST"}))
(def patch+ (partial-opts request {:method "PATCH"}))
