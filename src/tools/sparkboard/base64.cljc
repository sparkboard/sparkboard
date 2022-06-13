(ns tools.sparkboard.base64
  #?(:clj (:import (java.util Base64))))

(defn decode-base64 [s]
  #?(:cljs (-> (.from js/Buffer s "base64")
               (.toString))
     :clj  (->> s
                (.decode (Base64/getDecoder))
                (String.))))

(defn encode-base64 [s]
  #?(:cljs (-> (new js/Buffer s)
               (.toString "base64"))
     :clj  (->> (.getBytes s)
                (.encode (Base64/getEncoder)))))
