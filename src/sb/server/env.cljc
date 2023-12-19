(ns sb.server.env
  (:require [applied-science.js-interop :as j]
            #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.java.shell :refer [sh]])
            #?(:cljs [cljs.reader :refer [read-string]])
            [sb.js-convert :refer [json->clj]]
            [shadow.resource]
            [sb.util :as u]
            [clojure.walk :as walk])
  #?(:clj (:import java.util.Base64))
  #?(:cljs (:require-macros [sb.server.env :refer [some-inline-resource]])))

(defn env-var
  ([k]
   #?(:cljs (j/get-in js/process [:env (name k)])
      :clj  (System/getenv (name k))))
  ([k not-found] (if-some [x (env-var k)]
                   x
                   not-found)))
#?(:clj
   (defn db-path
     ([]
      (let [parent (env-var :DB_DIR "./.db")]
        (sh "mkdir" "-p" parent)
        parent))
     ([subdir]
      (str (db-path) (u/ensure-prefix subdir "/")))))

(defn parse-config [c]
  (some->> c
           (read-string)
           (walk/postwalk #(cond-> %
                                   (map? %)
                                   (u/update-some {:firebase/app-config json->clj
                                                   :firebase/service-account json->clj})))))

(defmacro some-inline-resource [path]
  (if (:ns &env)
    (when (some? (io/resource path))
      `(shadow.resource/inline ~path))
    `(some-> (io/resource ~path) slurp)))

(defn read-config []
  (parse-config (or (env-var :SPARKBOARD_CONFIG)
                    (some-inline-resource ".local.config.edn"))))
(def config
  (or (read-config) {}))

(def client-config
  (-> config
      (select-keys [:firebase/app-config
                    :sparkboard/jvm-root
                    :env])
      (assoc :slack/app-id (-> config :slack :app-id))))
