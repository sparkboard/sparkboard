(ns build
  (:require [uberdeps.api :as uberdeps]))

(defn aot [_]
  (compile 'org.sparkboard.server))

(defn uberjar [_]
  (let [deps (clojure.edn/read-string (slurp "deps.edn"))]
    (uberdeps/package deps "target/sparkboard.jar"
                      {:main-class "org.sparkboard.server"
                       :aliases [:datalevin]})))