(ns build
  (:require [uberdeps.api :as uberdeps]))

(defn aot [_]
  (compile 'org.sparkboard.server.server))

(defn uberjar [_]
  (let [exclusions (into uberdeps/exclusions [#"\.DS_Store" #".*\.cljs" #"cljsjs/.*"])
        deps (clojure.edn/read-string (slurp "deps.edn"))]
    (binding [uberdeps/exclusions exclusions
              uberdeps/level :warn]
      (uberdeps/package deps "target/sparkboard.jar"))))