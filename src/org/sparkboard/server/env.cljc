(ns org.sparkboard.server.env
  (:require [applied-science.js-interop :as j]
            #?(:cljs [cljs.reader :refer [read-string]])
            [org.sparkboard.resource :as rc]
            [org.sparkboard.js-convert :refer [json->clj clj->json]]
            [org.sparkboard.firebase.config :as fire-config])
  #?(:clj (:import java.util.Base64)))

(defn env-var [k]
  #?(:cljs (j/get-in js/process [:env (name k)])
     :clj  (System/getenv (name k))))

(defn update-some [m updaters]
  (reduce-kv (fn [m k update-fn]
               (if (contains? m k)
                 (update m k update-fn)
                 m)) m updaters))

(def config (-> (read-string
                  (or (env-var :SPARKBOARD_CONFIG)
                      (rc/some-inline-resource "/.local.config.edn")))
                (update-some {:firebase/app-config json->clj
                              :firebase/service-account json->clj})))

(defn init-config []
  (fire-config/set-firebase-config!
    (select-keys config [:firebase/app-config
                         :firebase/database-secret
                         :firebase/service-account])))

#?(:clj (init-config))
