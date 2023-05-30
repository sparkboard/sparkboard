(ns sparkboard.slack.firebase.admin-db-api
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [sparkboard.server.env :as env]
            [sparkboard.js-convert :refer [clj->json ->clj]]
            #?(:clj  [sparkboard.slack.firebase.jvm :as fire-jvm]
               :cljs [sparkboard.http :as http])
            [sparkboard.util :as u])
  #?(:clj (:import (com.google.firebase.database DatabaseReference))))

#?(:cljs
   (def database-url (-> env/config :firebase/app-config :databaseURL (str/replace #"/$" ""))))

#?(:cljs
   (defn firebase-fetch [method]
     (fn [path & [opts]]
       (http/request (str database-url (str/replace-first path #"^/*" "/") ".json")
                     (-> opts
                         (assoc :method method)
                         (update :query-params #(-> (if (map? %) % (apply hash-map %))
                                                    (assoc :auth (:firebase/database-secret env/config))
                                                    (u/update-some {:orderBy clj->json
                                                                    :startAt clj->json
                                                                    :endAt   clj->json
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
             :clj  (fn [path & [{:keys [query-params]}]]
                     (-> (fire-jvm/->ref path)
                         (cond-> query-params (apply-query query-params))
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
