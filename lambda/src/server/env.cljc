(ns server.env
  (:require #?@(:clj  [[clojure.java.io :as io]
                       [net.cgrand.macrovich :as m]]
                :cljs [shadow.resource]))
  #?(:cljs (:require-macros server.env
                            [net.cgrand.macrovich :as m])))

#?(:clj
   (defmacro some-inline-resource
     "Returns contents of resource in classpath, or nil if not present (compile-time)"
     [path]
     ;; shadow.resource/inline supports paths beginning with . as
     ;; relative to the containing file, but we don't support that here.
     (let [path (clojure.string/replace path #"^/" "")]
       (m/case :cljs
               (when (io/resource path)
                 `(~'shadow.resource/inline ~(str "/" path)))
               :clj
               `(some-> (io/resource ~path) slurp)))))
