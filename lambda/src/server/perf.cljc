(ns server.perf
  (:refer-clojure :exclude [time])
  #?(:cljs (:require [kitchen-async.promise :as p]
                     [applied-science.js-interop :as j]))
  #?(:cljs (:require-macros [server.perf :as perf])))

#?(:cljs
   (defn time-promise [label x]
     (let [t0 (js/Date.now)]
       (p/let [result x]
         (js/console.log (str "[promise " label "] " (- (js/Date.now) t0) "ms"))
         result))))

#?(:clj
   (defmacro time [label & body]
     `(let [t0# (~'js/Date.now)
            value# (do ~@body)]
        (~'js/console.log (str "[time " ~label "] " (- (~'js/Date.now) t0#) "ms"))
        value#)))


#?(:cljs
   (do

     (defn req-measure [k]
       (fn [req res next]
         (if-let [start (j/get req k)]
           (js/console.log (str "[" k "] " (- (js/Date.now) start)))
           (j/assoc! req k (js/Date.now)))
         (next)))

     (defn rounded [n pow] (-> n (* (Math/pow 10 pow)) (Math/round) (/ (Math/pow 10 pow))))
     (defn time-since [ts] (- (js/Date.now) ts))
     (defn seconds [n] (-> n (/ 1000) (rounded 2)))
     (def seconds-since (comp seconds time-since))
     (defn slack-ts-ms [ts]
       (-> (js/parseFloat ts) (* 1000)))))

#?(:clj
   (defn slack-ts-ms [ts]
     (* 1000 (float ts))))
