(ns org.sparkboard.client.loaders
  (:require [applied-science.js-interop :as j]
            [org.sparkboard.util.promise :as p]))

;; memoized loaders for css and javascript assets.
;; (at least the js needs to be dynamic because it depends on the user's locale)

(def css!
  (memoize
    (fn [url]
      (p/promise [resolve reject]
        (j/call-in js/document [:head :appendChild]
                   (-> (j/call js/document :createElement "link")
                       (j/assoc! :rel "stylesheet"
                                 :type "text/css"
                                 :href url
                                 :onerror #(do (js/console.error %) (resolve))
                                 :onload resolve)))))))

(def js!
  (memoize
    (fn [url]
      (p/promise [resolve reject]
        (j/call-in js/document [:body :appendChild]
                   (-> (j/call js/document :createElement "script")
                       (j/assoc! :type "text/javascript"
                                 :src url
                                 :onerror #(do (js/console.error %) (resolve))
                                 :onload resolve)))))))
