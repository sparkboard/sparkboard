(ns org.sparkboard.firebase-config
  (:require [org.sparkboard.js-convert :as jc]))

(defonce config (atom nil))

(defn set-firebase-config! [x]
  {:pre [((every-pred :firebase/service-account :firebase/database-secret :firebase/app-config) x)]}
  (reset! config #?(:cljs (jc/->clj x)
                    :clj x)))
