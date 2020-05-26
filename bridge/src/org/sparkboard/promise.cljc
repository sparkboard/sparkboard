(ns org.sparkboard.promise
  (:refer-clojure :exclude [let -> ->> do resolve try catch])
  #?(:cljs (:require kitchen-async.promise
                     [clojure.walk :as walk])
     :clj  (:require [net.cgrand.macrovich :as m]
                     [clojure.walk :as walk]))
  #?(:cljs
     (:require-macros [net.cgrand.macrovich :as m]
                      kitchen-async.promise
                      org.sparkboard.promise)))

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
           :clj :throw} ~x))

  (defmacro then [x]
    `(alt {:cljs kitchen-async.promise/then
           :clj :throw} ~x))

  (defmacro try [& body]
    `(alt {:cljs kitchen-async.promise/try
           :clj :throw} ~@(walk/postwalk-replace
                            {'p/catch 'kitchen-async.promise/catch} body)))

  (defmacro promise [& body]
    `(alt {:cljs kitchen-async.promise/promise
           :clj :throw} ~@body))

  (defmacro timeout [& body]
    `(alt {:cljs kitchen-async.promise/timeout
           :clj :throw} ~@body)))

