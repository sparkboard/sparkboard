(ns server.common
  (:require [applied-science.js-interop :as j]
            [cljs.reader]))

(def config
  (cljs.reader/read-string (j/get js/process.env :SPARKBOARD_CONFIG)))

(def firebase
  (js->clj (.parse js/JSON (:firebase/app-config config))
           :keywordize-keys true))

(defn parse-json [maybe-json]
  (try (.parse js/JSON maybe-json)
       (catch js/Error e
           e)))

(defn decode-base64 [s]
  (.toString (.from js/Buffer s "base64")))

(defn clj->json [x]
  (.stringify js/JSON (clj->js x)))
