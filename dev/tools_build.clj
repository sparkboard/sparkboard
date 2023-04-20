(ns dev.tools-build
  (:require [clojure.tools.build.api :as b]))

(defn compile-java [_]
      (b/javac {:src-dirs ["java"]
                :class-dir "classes"
                :basis (b/create-basis {:project "deps.edn"})
                :javac-opts ["-source" "8" "-target" "8"]}))