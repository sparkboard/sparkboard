(ns org.sparkboard.server.env
  (:require [applied-science.js-interop :as j]
            #?(:cljs [cljs.reader :refer [read-string]])
            [org.sparkboard.resource :as rc]
            [org.sparkboard.js-convert :refer [json->clj clj->json]])
  #?(:clj (:import java.util.Base64)))

(defn env-var [k]
  #?(:cljs (j/get-in js/process [:env (name k)])
     :clj  (System/getenv (name k))))

(defn update-some [m updaters]
  (reduce-kv (fn [m k update-fn]
               (if (contains? m k)
                 (update m k update-fn)
                 m)) m updaters))

(defn read-config []
  (some-> (or (env-var :SPARKBOARD_CONFIG)
              (rc/some-inline-resource "/.local.config.edn"))
          (read-string)
          (update-some {:firebase/app-config json->clj
                        :firebase/service-account json->clj})))

(def config
  (or (read-config) {}))
