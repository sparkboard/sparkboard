(ns server.common
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [cognitect.transit :as transit]
            [shadow.resource :as rc]
            [clojure.string :as str]))

(defn env-var [k]
  (j/get-in js/process [:env (name k)]))

(def config (cljs.reader/read-string
             (or (rc/inline "/.local.config.edn")
                 (env-var :SPARKBOARD_CONFIG))))

(def reader (transit/reader :json))

(defn read-transit [x]
  (transit/read reader x))

(def writer (transit/writer :json))

(defn write-transit [x]
  (transit/write writer x))

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

(j/defn lambda-root-url [^:js {:keys [headers url query]}]
  (let [host (j/get headers :host)]
    (str "https://" host (str/replace url #"(/slack.*)|/$" ""))))