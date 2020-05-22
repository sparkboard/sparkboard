(ns org.sparkboard.firebase-rest
  (:require [clojure.string :as str]
            [kitchen-async.promise :as p]
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
  (doto (str (-> @config :firebase/app-config :databaseURL)
             (str/replace-first path #"^/*" "/")            ;; needs a single leading "/"
             ".json"
             "?"
             (-> query
                 (update-some {:orderBy clj->json
                                      :startAt clj->json
                                      :endAt clj->json
                                      :equalTo clj->json})
                 (assoc :auth (:firebase/database-secret @config))
                 uri/map->query-string))
    (->> (prn :json-url))))

(defn req-fn [method]
  (fn [path & [{:as opts :keys [body]}]]
    (http/fetch-json+ (json-url path opts)
                      (-> {:method method}
                          (cond-> body (assoc :body (clj->json body)))
                          (clj->js)))))

(def get+ (req-fn "GET"))
(def put+ (req-fn "PUT"))
(def post+ (req-fn "POST"))
(def patch+ (req-fn "PATCH"))

(defn map->list [id-key m]
  (reduce-kv (fn [out key value] (conj out (assoc value id-key (name key)))) [] m))

(defn obj->list [id-key m]
  (map->list id-key (->clj m)))
