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

(j/defn lambda-root-url [^:js {:keys [headers url query]}]
  (let [host (j/get headers :host)]
    (str "https://" host (str/replace url #"(/slack.*)|/$" ""))))

(fire-config/set-firebase-config!
  (-> config
      (select-keys [:firebase/app-config
                    :firebase/database-secret
                    :firebase/service-account])
      (update :firebase/app-config json->clj)
      (update :firebase/service-account json->clj)))
