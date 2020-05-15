(ns server.dev
  (:require [applied-science.js-interop :as j]
            ["lambda-local" :as llocal]))

(comment
  (llocal/watch (j/lit {:port 8008
                        :lambda-path "./target/main.js"})))