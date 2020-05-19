(ns server.common
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [shadow.resource :as rc]))

(def config (cljs.reader/read-string
              (or (j/get js/process.env :SPARKBOARD_CONFIG)
                  (rc/inline "/.local.config.edn"))))

(defn parse-json [maybe-json]
  (try (.parse js/JSON maybe-json)
       (catch js/Error e
           e)))

(defn json->clj [json]
  (-> (js/JSON.parse json)
      (js->clj :keywordize-keys true)))

(defn update-some [m updaters]
  (reduce-kv (fn [m k update-fn]
               (if (contains? m k)
                 (update m k update-fn)
                 m)) m updaters))

(defn decode-base64 [s]
  (.toString (.from js/Buffer s "base64")))

(defn clj->json [x]
  (.stringify js/JSON (clj->js x)))


(def firebase-app-config
  (js->clj (.parse js/JSON (:firebase/app-config config))
           :keywordize-keys true))

(def firebase-service-account
  (delay (-> config :firebase/service-account json->clj)))

(def firebase-database-secret (:firebase/database-secret config))

