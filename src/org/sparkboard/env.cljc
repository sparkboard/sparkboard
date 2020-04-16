(ns org.sparkboard.env
  (:refer-clojure :exclude [get])
  (:require #?@(:clj
                [[aero.core :as aero]
                 [clojure.java.io :as io]])
            [shadow-env.core :as env])
  #?(:cljs (:require-macros org.sparkboard.env)))

#?(:clj
   (do

     (defn merge-maps [m1 m2]
       (let [maybe-map? (fn [m] (or (nil? m) (map? m)))]
         (merge-with #(if (and (maybe-map? %1) (maybe-map? %2))
                        (merge %1 %2)
                        %2)
                     m1 m2)))

     (defn read-env [_]
       (let [aero-config {:profile (or (System/getenv "ENV") :dev)}
             [config local-config] (->> ["org/sparkboard/config.edn"
                                         "org/sparkboard/.local.config.edn"]
                                        (map #(some-> (io/resource %)
                                                      (aero/read-config aero-config))))
             merged-config (fn [k] (merge-maps (k config) (k local-config)))]
         {:common (merged-config :common)
          :clj (merged-config :clj)
          :cljs (merged-config :cljs)}))))

(env/link get `read-env)