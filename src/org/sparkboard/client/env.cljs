(ns org.sparkboard.client.env
  (:require [org.sparkboard.transit :as transit]
            [applied-science.js-interop :as j]))

(def config (-> (j/get-in js/window [:SPARKBOARD_CONFIG :textContent])
                transit/read))


