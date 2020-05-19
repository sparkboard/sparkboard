(ns server.tokens
  (:require ["jwt-simple" :as jwt]
            [applied-science.js-interop :as j]
            [server.common :as common]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Firebase token encode/decode for secure communication with legacy services

(def firebase-service-account
  (delay (-> common/config :firebase/service-account common/json->clj)))

(defn firebase-encode [claims]
  {:pre [(map? claims)]}
  (let [now-seconds (-> (js/Date.now) (/ 1000))]
    (jwt/encode (j/obj
                  :alg "RS256"
                  :iss (:client_email @firebase-service-account)
                  :sub (:client_email @firebase-service-account)
                  :aud "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit"
                  :iat now-seconds
                  :exp (+ now-seconds 3600)
                  :uid "lambda:slack"
                  :claims (clj->js claims))
                (:private_key @firebase-service-account))))

(defn firebase-decode [token]
  (-> (jwt/decode token (:private_key @firebase-service-account))
      (j/get :claims)
      (js->clj :keywordize-keys true)))

(comment
  (let [payload {:hello 1 :there "world"}]
    (= payload
       (firebase-decode (firebase-encode payload)))))