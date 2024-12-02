(ns sb.app.time
  "Time and date formating functions")

#?(:cljs
   (defn small-timestamp [date]
     (let [now (js/Date.)
           current-year? (= (.getFullYear now)
                            (.getFullYear date))
           current-month? (and current-year? (= (.getMonth now)
                                                (.getMonth date)))
           current-day? (and current-month? (= (.getDate now)
                                               (.getDate date)))]
       (.format (js/Intl.DateTimeFormat. js/undefined
                                         (clj->js (merge {:minute :numeric
                                                          :hour :numeric}
                                                         (when-not current-day?
                                                           {:day :numeric
                                                            :weekday :long
                                                            :month :long})
                                                         (when-not current-year?
                                                           {:year :numeric}))))
                date))))

(defn small-datestamp [date]
  #?(:clj
     ;; TODO make small
     ;; TODO use user's timezone
     (format "%tY-%<tm-%<td" date)

     :cljs
     (let [now (js/Date.)
           current-year? (= (.getFullYear now)
                            (.getFullYear date))
           current-month? (and current-year? (= (.getMonth now)
                                                (.getMonth date)))
           current-day? (and current-month? (= (.getDate now)
                                               (.getDate date)))]
       ;; TODO today as today
       (.format (js/Intl.DateTimeFormat. js/undefined
                                         (clj->js (merge {:day :numeric
                                                          :weekday :long}
                                                         (when-not current-day?
                                                           {:month :long})
                                                         (when-not current-year?
                                                           {:year :numeric}))))
                date))))


(comment
  (small-datestamp (java.util.Date.))

  )
