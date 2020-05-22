(ns org.sparkboard.firebase-tokens
  (:require ["jwt-simple" :as jwt]
            [org.sparkboard.js-convert :refer [->js ->clj]]
            [applied-science.js-interop :as j]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Firebase token encode/decode for secure communication with legacy services

(defn encode [service-account claims]
  {:pre [(map? claims)]}
  (let [now-seconds (-> (js/Date.now) (/ 1000))]
    (jwt/encode (j/obj
                  :alg "RS256"
                  :iss (:client_email service-account)
                  :sub (:client_email service-account)
                  :aud "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit"
                  :iat now-seconds
                  :exp (+ now-seconds 3600)
                  :uid "lambda:slack"
                  :claims (->js claims))
                (:private_key service-account))))

(defn decode [service-account token]
  (-> (jwt/decode token (:private_key service-account))
      (j/get :claims)
      (->clj)))

(comment
  (let [opts {:client_email "a@b.c"
              :private_key "xyz"}
        payload {:hello 1
                 :there "world"
                 :hello/there "world"}]
    (= payload
       (decode opts (encode opts payload)))))