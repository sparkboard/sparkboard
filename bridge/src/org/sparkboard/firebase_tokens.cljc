(ns org.sparkboard.firebase-tokens
  (:require [org.sparkboard.firebase-config :refer [config]]
            [org.sparkboard.js-convert :refer [->js ->clj json->clj clj->json]]
            [org.sparkboard.jwt-rs256 :as jwt]
            #?(:clj [clj-time.core :as time])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Firebase token encode/decode for secure communication with legacy services

(def creds (delay (:firebase/service-account @config)))

(defn now []
  #?(:cljs (-> (js/Date.now) (/ 1000))
     :clj  (time/now)))

(defn +seconds [t n]
  #?(:cljs (+ t n)
     :clj  (time/plus t (time/seconds n))))

(defn encode [claims]
  (let [now (now)
        {:keys [private_key client_email]} @creds
        jwt-claims {:alg :RS256
                    :iss client_email
                    :sub client_email
                    :aud "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit"
                    :iat now
                    :exp (+seconds now 3600)
                    :uid (str ::encode #?(:clj ".clj" :cljs ".cljs"))
                    ;; nested claims field for app data
                    :claims claims}]
    (jwt/encode jwt-claims private_key)))

(defn decode [token]
  (let [decoded (jwt/decode token (-> @creds :private_key))]
    (with-meta (:claims decoded) decoded)))

(comment
  (let [payload {:name "Jerry"}]
    (meta (decode (encode payload)))))
