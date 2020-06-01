(ns org.sparkboard.util)

(defn guard [x f]
  (when (f x) x))
