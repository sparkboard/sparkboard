(ns server.common
  (:require [applied-science.js-interop :as j]
            [cljs.reader]))

(def config
  (cljs.reader/read-string (j/get js/process.env :SPARKBOARD_CONFIG)))

(def firebase
  (js->clj (.parse js/JSON (:firebase/app-config config))
           :keywordize-keys true))
