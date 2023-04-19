(ns sparkboard.jwt-rs256
  (:require [clojure.walk :as walk]
            [sparkboard.js-convert :refer [->js ->clj]]
            #?@(:cljs [["jsonwebtoken" :as jwt]]
                :clj  [[clj-jwt.core :as jwt]
                       [clj-jwt.key :as jwt-key]]))
  #?(:clj (:import [java.io StringReader])))

(defn now-in-seconds []
  (-> #?(:clj  (-> (inst-ms (java.time.Instant/now))
                   (/ 1000)
                   long)
         :cljs (-> (js/Date.now)
                   (/ 1000)))))

(defn +seconds [t n]
  (+ t n))

#?(:clj
   (def key->string
     (memoize
      (fn [kind key-str]
        (with-open [r (StringReader. key-str)]
          (case kind
            :private
            (jwt-key/pem->private-key r nil)
            :public
            (jwt-key/pem->public-key r nil)))))))

(defn encode [claims key]
  #?(:clj  (-> (->js claims)
               (jwt/jwt)
               (jwt/sign :RS256 (key->string :private key))
               jwt/to-str)
     :cljs (-> (->js claims)
               (jwt/sign key #js{:algorithm "RS256"}))))

(defn verify-time [{:as claims :keys [exp]}]
  (when (< exp (now-in-seconds))
    (throw (ex-info "Expired token" {:claims claims})))
  claims)

(defn decode [token key]
  #?(:clj  (-> (jwt/str->jwt token)
               (doto (jwt/verify :RS256 (key->string :public key)))
               :claims
               walk/keywordize-keys
               verify-time)
     :cljs (-> (jwt/verify token key)
               ->clj
               verify-time)))
