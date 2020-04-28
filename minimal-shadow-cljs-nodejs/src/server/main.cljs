(ns server.main
  "AWS Lambda <--> Slack API"
  (:require [applied-science.js-interop :as j]))

;; Original template from https://github.com/minimal-xyz/minimal-shadow-cljs-nodejs

(defn handler [event _context callback]
  ;; https://docs.aws.amazon.com/lambda/latest/dg/nodejs-handler.html
  (callback nil
            #js {:statusCode 200
                 ;; For the Slack API check
                 ;; TODO (low priority) https://api.slack.com/authentication/verifying-requests-from-slack
                 :body (j/get (.parse js/JSON (j/get event :body)) :challenge)}))
