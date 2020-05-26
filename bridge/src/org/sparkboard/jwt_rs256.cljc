(ns org.sparkboard.jwt-rs256
  #?(:cljs (:require ["jwt-simple" :as jwt-simple]
                     [org.sparkboard.js-convert :refer [->js ->clj]])
     :clj  (:require [clj-jwt.core :as jwt]
                     [clj-jwt.key :as jwt-key]))
  #?(:clj (:import [java.io StringReader])))

#?(:clj
   (def private-key-from-string
     (memoize
       (fn [key-str]
         (with-open [r (StringReader. key-str)]
           (jwt-key/pem->private-key r nil))))))

(defn encode [claims key]
  #?(:clj  (-> (jwt/jwt claims)
               (jwt/sign :RS256 (private-key-from-string key))
               jwt/to-str)
     :cljs (-> (->js claims)
               (jwt-simple/encode key))))

(defn decode [token key]
  #?(:clj  (-> (jwt/str->jwt token)
               (jwt/sign :RS256 (private-key-from-string key))
               :claims)
     :cljs (-> (jwt-simple/decode token key)
               ->clj)))
