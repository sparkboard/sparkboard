(ns org.sparkboard.firebase.client
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [lambdaisland.uri :as uri]
            [org.sparkboard.firebase.config :refer [config]]
            [org.sparkboard.js-convert :refer [clj->json ->clj]]
            #?(:clj  [org.sparkboard.firebase.jvm :as fire-jvm]
               :cljs [org.sparkboard.http :as http]))
  (:import (com.google.firebase.database DatabaseReference)))

#?(:cljs
   (defn update-some [m updaters]
     (reduce-kv (fn [m k update-fn]
                  (if (contains? m k)
                    (update m k update-fn)
                    m)) m updaters)))

#?(:cljs
   (defn json-url [path & [{:keys [query]}]]
     (str (-> @config :firebase/app-config :databaseURL (str/replace #"/$" ""))
          (str/replace-first path #"^/*" "/")               ;; needs a single leading "/"
          ".json"
          "?"
          (-> (apply hash-map query)
              (update-some {:orderBy clj->json
                            :startAt clj->json
                            :endAt clj->json
                            :equalTo clj->json})
              (assoc :auth (:firebase/database-secret @config))
              uri/map->query-string))))

#?(:cljs
   (defn partial-opts [http-fn extra-opts]
     (fn [path & [opts]]
       (http-fn (json-url path opts) (merge extra-opts opts)))))

#?(:clj
   (defn- apply-query [ref query]
     (reduce (fn [^DatabaseReference ref [k v]]
               (case k
                 :orderByChild (.orderByChild ref v)
                 :orderByKey (.orderByKey ref)
                 :orderByValue (.orderByValue ref)
                 :limitToFirst (.limitToFirst ref v)
                 :limitToLast (.limitToLast ref v)
                 :startAt (.startAt ref v)
                 :equalTo (.equalTo ref v)))
             ref
             (partition 2 query))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API

(def read #?(:cljs (partial-opts http/http-req {:method "GET"})
             :clj  (fn [path & [{:keys [query]}]]
                     (-> (fire-jvm/->ref path)
                         (cond->> query (apply-query query))
                         (fire-jvm/read)))))

;; replace value at path
(def set-value #?(:cljs (partial-opts http/http-req {:method "PUT"})
                  :clj  (fn [path & [{:keys [body]}]]
                          (fire-jvm/set-value path body))))

;; merge value at path
(def update-value #?(:cljs (partial-opts http/http-req {:method "PATCH"})
                     :clj  (fn [path & [{:keys [body]}]]
                             (fire-jvm/update-value path body))))

(defn map->list [id-key m]
  (reduce-kv (fn [out key value] (conj out (assoc value id-key (name key)))) [] m))
