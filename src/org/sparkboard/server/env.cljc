(ns org.sparkboard.server.env
  (:require [applied-science.js-interop :as j]
            #?(:cljs [cljs.reader :refer [read-string]])
            [tools.sparkboard.js-convert :refer [json->clj]]
            [tools.sparkboard.resources :as rc]
            [tools.sparkboard.util :as u])
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
