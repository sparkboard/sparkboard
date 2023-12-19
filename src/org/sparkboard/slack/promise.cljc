(ns org.sparkboard.slack.promise
  (:refer-clojure :exclude [let -> ->> do resolve try catch promise])
  (:require [kitchen-async.promise]
            #?(:clj [net.cgrand.macrovich :as m]))
  #?(:cljs
     (:require-macros [net.cgrand.macrovich :as m]
                      org.sparkboard.slack.promise)))

(m/deftime
 (defmacro alt [{:keys [cljs clj]} & body]
   (m/case
    :cljs (list* cljs body)
    :clj (case clj
           :throw (throw (ex-info (str cljs " not implemented for clj") {}))
           (list* clj body))))

 (defmacro let [& body]
   `(alt {:cljs kitchen-async.promise/let
          :clj clojure.core/let} ~@body))

 (defmacro -> [& body]
   `(alt {:cljs kitchen-async.promise/->
          :clj clojure.core/->} ~@body))

 (defmacro ->> [& body]
   `(alt {:cljs kitchen-async.promise/->>
          :clj clojure.core/->>} ~@body))

 (defmacro do [& body]
   `(alt {:cljs kitchen-async.promise/do
          :clj clojure.core/do} ~@body))

 (defmacro resolve [x]
   `(alt {:cljs kitchen-async.promise/resolve
          :clj :throw} ~x))

 (defmacro all [x]
   `(alt {:cljs kitchen-async.promise/all
          :clj vec} ~x))

 (defmacro promise [& body]
   `(alt {:cljs kitchen-async.promise/promise
          :clj :throw} ~@body))

 (defmacro timeout [& body]
   `(alt {:cljs kitchen-async.promise/timeout
          :clj :throw} ~@body)))

