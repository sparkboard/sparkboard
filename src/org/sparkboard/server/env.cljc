(ns org.sparkboard.server.env
  (:require [applied-science.js-interop :as j]
            #?(:cljs [cljs.reader :refer [read-string]])
            [org.sparkboard.util :as u]
            [org.sparkboard.util.js-convert :refer [json->clj]]
            [org.sparkboard.util.resources :as rc])
  #?(:clj (:import java.util.Base64)))

(defn env-var [k]
  #?(:cljs (j/get-in js/process [:env (name k)])
     :clj  (System/getenv (name k))))

(defn read-config []
  (some-> (or (env-var :SPARKBOARD_CONFIG)
              (rc/some-inline-resource "/.local.config.edn"))
          (read-string)
          (u/update-some {:firebase/app-config json->clj
                          :firebase/service-account json->clj})))

(def config
  (or (read-config) {}))

(def client-config
  (-> config
      (select-keys [:firebase/app-config :sparkboard/jvm-root])
      (assoc :slack/app-id (-> config :slack :app-id))))
