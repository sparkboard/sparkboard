(ns server.env
  (:require #?(:clj [clojure.java.io :as io]
               :cljs shadow.resource))
  #?(:cljs (:require-macros server.env)))

#?(:clj
   (defmacro some-inline-resource [resource-path]
     {:pre [(string? resource-path)]}
     (when (io/resource resource-path)
       `(~'shadow.resource/inline ~resource-path))))
