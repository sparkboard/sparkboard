(ns org.sparkboard.macros
  (:require [clojure.walk :as walk]
            [shadow.lazy #?(:clj :as-alias :cljs :as) lazy])
  #?(:cljs (:require-macros org.sparkboard.macros)))

(defmacro lazy-views
  ;; wraps :browse keys with lazy/loadable (and resolves aliases, with :as-alias support)
  [expr]
  (let [aliases (ns-aliases *ns*)
        resolve-sym (fn [sym]
                      (if-let [resolved (get aliases (symbol (namespace sym)))]
                        (symbol (str resolved) (name sym))
                        sym))]
    (walk/postwalk
     (fn [x]
       (if-let [view (:view x)]
         (if (:ns &env)
           (assoc x :view `(lazy/loadable ~(resolve-sym (second view))))
           (dissoc x :view))
         x))
     expr)))