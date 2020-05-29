(ns org.sparkboard.util.future)

(defmacro try-future [& body]
  `(future
     (try
       ~@body
       (catch Exception e#
         (~'taoensso.timbre/error :future/Exception e#))
       (catch java.lang.AssertionError e#
         (~'taoensso.timbre/error {:future/AssertionError e#})))))
