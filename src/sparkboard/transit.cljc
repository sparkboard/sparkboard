(ns sparkboard.transit
  (:refer-clojure :exclude [read])
  (:require [cognitect.transit :as transit]
            #?(:clj [clojure.java.io :as io]))
  #?(:clj (:import [java.io ByteArrayInputStream ByteArrayOutputStream])))

#?(:cljs
   (def reader (transit/reader :json)))

(defn read [x]
  #?(:cljs
     (transit/read reader x)
     :clj
     (with-open [s (io/input-stream (.getBytes x))]
       (-> (transit/reader s :json)
           transit/read))))

#?(:cljs
   (def writer (transit/writer :json)))

(defn write [x]
  #?(:cljs
     (transit/write writer x)
     :clj
     (let [baos (ByteArrayOutputStream.)
           w (transit/writer baos :json)
           _ (transit/write w x)
           ret (.toString baos)]
       (.reset baos)
       ret)))

(comment
  (read
    (write
      {:hello "there"})))
