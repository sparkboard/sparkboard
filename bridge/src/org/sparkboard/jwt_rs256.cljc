(ns org.sparkboard.jwt-rs256
  #?(:cljs (:require ["jwt-simple" :as jwt-simple]
                     [org.sparkboard.js-convert :refer [->js ->clj]])
     :clj  (:require [clj-jwt.core :as jwt]
                     [clj-jwt.key :as jwt-key]))
  #?(:clj (:import [java.io StringReader])))

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
  #?(:clj  (-> (jwt/jwt claims)
               (jwt/sign :RS256 (key->string :private key))
               jwt/to-str)
     :cljs (-> (->js claims)
               (jwt-simple/encode key))))

(defn decode [token key]
  #?(:clj  (-> token
               jwt/str->jwt
               (doto (jwt/verify :RS256 (key->string :public key)))
               :claims)
     :cljs (-> (jwt-simple/decode token key)
               ->clj)))
