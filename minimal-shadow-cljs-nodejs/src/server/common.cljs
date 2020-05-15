(ns server.common
  (:require [applied-science.js-interop :as j]
            [cljs.reader]
            [shadow.resource :as rc]))

(def config (cljs.reader/read-string
              (or (j/get js/process.env :SPARKBOARD_CONFIG)
                  (rc/inline "/.local.config.edn"))))

(def firebase
  (js->clj (.parse js/JSON (:firebase/app-config config))
           :keywordize-keys true))

(defn parse-json [maybe-json]
  (try (.parse js/JSON maybe-json)
       (catch js/Error e
           e)))

(defn decode-base64 [s]
  ;; TODO "DeprecationWarning: Buffer() is deprecated due to security
  ;; and usability issues. Please use the Buffer.alloc(),
  ;; Buffer.allocUnsafe(), or Buffer.from() methods instead."
  (.toString (js/Buffer s "base64")))
