(ns org.sparkboard.firebase.admin-db-api
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [lambdaisland.uri :as uri]
            [org.sparkboard.server.env :as env]
            [tools.sparkboard.js-convert :refer [clj->json ->clj]]
            #?(:clj  [org.sparkboard.firebase.jvm :as fire-jvm]
               :cljs [tools.sparkboard.http :as http])
            [tools.sparkboard.util :as u])
  #?(:clj (:import (com.google.firebase.database DatabaseReference))))

#?(:cljs
   (def database-url (-> env/config :firebase/app-config :databaseURL (str/replace #"/$" ""))))

#?(:cljs
   (defn firebase-fetch [method]
     (fn [path & [opts]]
       (http/http-req (str database-url (str/replace-first path #"^/*" "/") ".json")
                      (-> opts
                          (assoc :method method)
                          (update :query #(-> (if (map? %) % (apply hash-map %))
                                              (assoc :auth (:firebase/database-secret env/config))
                                              (u/update-some {:orderBy clj->json
                                                              :startAt clj->json
                                                              :endAt clj->json
                                                              :equalTo clj->json}))))))))

#?(:clj
   (defn- apply-query [ref query]
     (reduce (fn [^DatabaseReference ref [k v]]
               (case k
                 (:orderBy
                   :orderByChild) (.orderByChild ref v)
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

(def read #?(:cljs (firebase-fetch "GET")
             :clj  (fn [path & [{:keys [query]}]]
                     (-> (fire-jvm/->ref path)
                         (cond-> query (apply-query query))
                         (fire-jvm/read)))))

;; replace value at path
(def set-value #?(:cljs (firebase-fetch "PUT")
                  :clj  (fn [path & [{:keys [body]}]]
                          (fire-jvm/set-value path body))))

;; merge value at path
(def update-value #?(:cljs (firebase-fetch "PATCH")
                     :clj  (fn [path & [{:keys [body]}]]
                             (fire-jvm/update-value path body))))

(defn map->list [id-key m]
  (reduce-kv (fn [out key value] (conj out (assoc value id-key (name key)))) [] m))
