(ns org.sparkboard.firebase.tokens
  (:require [org.sparkboard.server.env :as env]
            [org.sparkboard.http :as http]
            [org.sparkboard.js-convert :refer [->js ->clj json->clj clj->json]]
            [org.sparkboard.jwt-rs256 :as jwt]
            [org.sparkboard.promise :as p]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Firebase token encode/decode for secure communication with legacy services

(def creds (:firebase/service-account env/config))

(defn encode [claims & [{:keys [expires-in]
                               :or {expires-in 3600}}]]
  (let [now (jwt/now-in-seconds)
        {:keys [private_key client_email]} creds
        jwt-claims {:alg :RS256
                    :iss client_email
                    :sub client_email
                    :aud "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit"
                    :iat now
                    :exp (jwt/+seconds now expires-in)
                    :uid (str ::encode #?(:clj ".clj" :cljs ".cljs"))
                    ;; nested claims field for app data
                    :claims claims}]
    (jwt/encode jwt-claims private_key)))

(def public-key
  ;; TODO
  ;; respect http caching headers, invalidate these accordingly
  (delay
    (p/-> (http/get+ (:client_x509_cert_url creds))
          (get (keyword (:private_key_id creds))))))

(defn decode [token]
  (p/let [key @public-key
          decoded (jwt/decode token key)]

    (with-meta (:claims decoded) decoded)))

(comment
  (p/let [claims {:name "Jerry"}
          decoded (decode (encode claims {:expires-in -1}))]
    (prn (= claims decoded))))
