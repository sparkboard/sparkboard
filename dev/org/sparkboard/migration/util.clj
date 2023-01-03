(ns org.sparkboard.migration.util
  (:require [java-time :as time])
  (:import (java.util Date)))

(defn date-time [inst] (time/local-date-time inst "UTC"))

(defn bson-id-timestamp [id]
  (new Date (* 1000 (java.lang.Integer/parseInt (subs id 0 8) 16))))

(defn days-between
  [date-time-1 date-time-2]
  (-> (time/duration (time/truncate-to date-time-1 :days)
                     (time/truncate-to date-time-2 :days))
      (time/as :days)))

(defn days-since [dt]
  (days-between dt (time/local-date-time)))