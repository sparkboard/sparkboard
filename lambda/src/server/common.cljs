(ns server.common
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [shadow.resource :as rc]
            [clojure.string :as str]
            [org.sparkboard.js-convert :refer [->js ->clj json->clj clj->json]]
            [org.sparkboard.firebase-tokens :as tokens]
            [org.sparkboard.firebase-config :as fire-config]))

(defn env-var [k]
  (j/get-in js/process [:env (name k)]))

(def config (cljs.reader/read-string
             (or (env-var :SPARKBOARD_CONFIG)
                 (rc/inline "/.local.config.edn"))))

(def aws? (or (env-var :LAMBDA_TASK_ROOT)
              (env-var :AWS_EXECUTION_ENV)))

(defn parse-json [maybe-json]
  (try (.parse js/JSON maybe-json)
       (catch js/Error e
         e)))

(defn update-some [m updaters]
  (reduce-kv (fn [m k update-fn]
               (if (contains? m k)
                 (update m k update-fn)
                 m)) m updaters))

(defn decode-base64 [s]
  (.toString (.from js/Buffer s "base64")))

(def lambda-path-prefix (when aws? "/Prod"))                ;; better way to discover lambda root at runtime?
(j/defn lambda-root-url [^:js {:keys [headers]}]
  (str "https://" (j/get headers :host) lambda-path-prefix))

(fire-config/set-firebase-config!
  (-> config
      (select-keys [:firebase/app-config
                    :firebase/database-secret
                    :firebase/service-account])
      (update :firebase/app-config json->clj)
      (update :firebase/service-account json->clj)))
