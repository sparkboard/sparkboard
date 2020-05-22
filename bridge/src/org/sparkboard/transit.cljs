(ns org.sparkboard.transit
  (:refer-clojure :exclude [read])
  (:require [cognitect.transit :as transit]))

(def reader (transit/reader :json))

(defn read [x]
  (transit/read reader x))

(def writer (transit/writer :json))

(defn write [x]
  (transit/write writer x))