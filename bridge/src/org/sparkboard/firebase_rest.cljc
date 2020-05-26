(ns org.sparkboard.firebase-rest
  (:require [clojure.string :as str]
            [lambdaisland.uri :as uri]
            [org.sparkboard.firebase-config :refer [config]]
            [org.sparkboard.http :as http]
            [org.sparkboard.js-convert :refer [clj->json ->clj]]))

(defn update-some [m updaters]
  (reduce-kv (fn [m k update-fn]
               (if (contains? m k)
                 (update m k update-fn)
                 m)) m updaters))

(defn json-url [path & [{:keys [query]}]]
  (str (-> @config :firebase/app-config :databaseURL (str/replace #"/$" ""))
       (str/replace-first path #"^/*" "/")            ;; needs a single leading "/"
       ".json"
       "?"
       (-> query
           (update-some {:orderBy clj->json
                         :startAt clj->json
                         :endAt clj->json
                         :equalTo clj->json})
           (assoc :auth (:firebase/database-secret @config))
           uri/map->query-string)))

(defn partial-opts [http-fn extra-opts]
  (fn [path & [opts]]
    (http-fn (json-url path opts) (merge extra-opts opts))))

(def get+ (partial-opts http/http-req {:method "GET"}))
(def put+ (partial-opts http/http-req {:method "PUT"}))
(def post+ (partial-opts http/http-req {:method "POST"}))
(def patch+ (partial-opts http/http-req {:method "PATCH"}))

(defn map->list [id-key m]
  (reduce-kv (fn [out key value] (conj out (assoc value id-key (name key)))) [] m))
