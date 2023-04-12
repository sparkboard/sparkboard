(ns org.sparkboard.macros
  (:require [yawn.view :as v]
            [re-db.react]
            [clojure.walk :as walk]
            [shadow.lazy #?(:clj :as-alias :cljs :as) lazy])
  #?(:cljs (:require-macros org.sparkboard.macros)))

(defmacro lazy-views
  ;; wraps :view keys with lazy/loadable (and resolves aliases, with :as-alias support)
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

(defmacro defview [name & args]
  (v/defview:impl
   {:wrap-expr (fn [expr] `(re-db.react/use-derefs ~expr))}
   name
   args))