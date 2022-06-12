(ns org.sparkboard.client.env
  (:require [applied-science.js-interop :as j]
            [org.sparkboard.util.transit :as transit]))

(def config
  (-> (j/get-in js/window [:SPARKBOARD_CONFIG :textContent])
      transit/read))

