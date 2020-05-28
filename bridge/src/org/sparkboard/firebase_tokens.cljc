(ns org.sparkboard.firebase-tokens
  (:require #?(:clj [clj-time.core :as time])
            [org.sparkboard.firebase-config :refer [config]]
            [org.sparkboard.http :as http]
            [org.sparkboard.js-convert :refer [->js ->clj json->clj clj->json]]
            [org.sparkboard.jwt-rs256 :as jwt]
            [org.sparkboard.promise :as p]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Firebase token encode/decode for secure communication with legacy services

(def creds (delay (:firebase/service-account @config)))

(defn now []
  #?(:cljs (-> (js/Date.now) (/ 1000))
     :clj  (time/now)))

(defn +seconds [t n]
  #?(:cljs (+ t n)
     :clj  (time/plus t (time/seconds n))))

(defn encode [{:as claims ::keys [expires-in]
               :or {expires-in 3600}}]
  (let [now (now)
        {:keys [private_key client_email]} @creds
        jwt-claims {:alg :RS256
                    :iss client_email
                    :sub client_email
                    :aud "https://identitytoolkit.googleapis.com/google.identity.identitytoolkit.v1.IdentityToolkit"
                    :iat now
                    :exp (+seconds now expires-in)
                    :uid (str ::encode #?(:clj ".clj" :cljs ".cljs"))
                    ;; nested claims field for app data
                    :claims (dissoc claims :token/expires-in)}]
    (jwt/encode jwt-claims private_key)))

(def public-key
  ;; TODO
  ;; respect http caching headers, invalidate these accordingly
  (delay
    (p/-> (http/get+ (:client_x509_cert_url @creds))
          (get (keyword (:private_key_id @creds))))))

(defn decode [token]
  (p/let [key @public-key
          decoded (jwt/decode token key)]
    (with-meta (:claims decoded) decoded)))

(comment
  (let [claims {:name "Jerry"}]
    (= claims (decode (encode claims)))))
