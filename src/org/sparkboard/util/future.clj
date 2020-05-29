(ns org.sparkboard.util.future)

(defmacro try-future [& body]
  `(future
     (try
       ~@body
       (catch Exception e#
         (tap> {:future/Exception e#}))
       (catch java.lang.AssertionError e#
         (tap> {:future/AssertionError e#})))))
