(ns server.common
  (:require [applied-science.js-interop :as j]
            #?(:cljs [cljs.reader :refer [read-string]])
            [server.env :as env]
            [org.sparkboard.js-convert :refer [json->clj clj->json]]
            [org.sparkboard.firebase-config :as fire-config])
  #?(:clj (:import java.util.Base64)))

(defn env-var [k]
  #?(:cljs (j/get-in js/process [:env (name k)])
     :clj  (System/getenv (name k))))

(def config (read-string
              (or (env-var :SPARKBOARD_CONFIG)
                  (env/some-inline-resource "/.local.config.edn"))))

(def aws? (or (env-var :LAMBDA_TASK_ROOT)
              (env-var :AWS_EXECUTION_ENV)))

(defn update-some [m updaters]
  (reduce-kv (fn [m k update-fn]
               (if (contains? m k)
                 (update m k update-fn)
                 m)) m updaters))

(defn decode-base64 [s]
  #?(:cljs (.toString (.from js/Buffer s "base64"))
     :clj  (String. (.decode (Base64/getDecoder) s))))

(def lambda-path-prefix (when aws? "/Prod"))                ;; better way to discover lambda root at runtime?

(defn req-host [req]
  #?(:cljs (j/get-in req [:headers :host])
     :clj  (get-in req [:headers "host"])))

(defn lambda-root-url [req]
  (str "https://" (req-host req) lambda-path-prefix))

(defn init-config []
  (fire-config/set-firebase-config!
    (-> config
        (select-keys [:firebase/app-config
                      :firebase/database-secret
                      :firebase/service-account])
        (update :firebase/app-config json->clj)
        (update :firebase/service-account json->clj))))

#?(:clj (init-config))
