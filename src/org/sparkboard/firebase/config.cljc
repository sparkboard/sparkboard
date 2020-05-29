(ns org.sparkboard.firebase.config
  (:require [org.sparkboard.js-convert :refer [->clj]]))

(defonce config (atom nil))

(defn set-firebase-config! [x]
  (let [data (->clj x)]
    (assert ((every-pred :firebase/service-account :firebase/database-secret :firebase/app-config) x))
    (reset! config data)))
