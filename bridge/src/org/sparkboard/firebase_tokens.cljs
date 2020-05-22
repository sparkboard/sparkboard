(ns org.sparkboard.firebase-tokens
  (:require ["jwt-simple" :as jwt]
            [applied-science.js-interop :as j]
            [org.sparkboard.firebase-config :refer [config]]
            [org.sparkboard.js-convert :refer [->js ->clj]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Firebase token encode/decode for secure communication with legacy services

(defn encode [claims]
  {:pre [(or (map? claims) (object? claims))]}
  (let [now-seconds (-> (js/Date.now) (/ 1000))
        {:keys [private_key client_email]} (:firebase/service-account @config)]
    (jwt/encode (j/obj
                  :alg "RS256"
                  :iss client_email
                  :sub client_email
                  :aud "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit"
                  :iat now-seconds
                  :exp (+ now-seconds 3600)
                  :uid "lambda:slack"
                  :claims (->js claims))
                private_key)))

(defn decode [token]
  (-> (jwt/decode token (-> @config :firebase/service-account :private_key))
      (j/get :claims)
      (->clj)))

(comment
  (let [opts {:client_email "a@b.c"
              :private_key "xyz"}
        payload {:hello 1
                 :there "world"
                 :hello/there "world"}]
    (= payload
       (decode (encode payload)))))