(ns org.sparkboard.macros
  (:require [yawn.view :as v]
            [re-db.react]
            [tools.sparkboard.util :as u]
            [clojure.walk :as walk]
            [shadow.lazy #?(:clj :as-alias :cljs :as) lazy])
  #?(:cljs (:require-macros org.sparkboard.macros)))

(defmacro E
  ;; wraps :view keys with lazy/loadable (and resolves aliases, with :as-alias support)
  [tag endpoint]
  (let [aliases (ns-aliases *ns*)
        resolve-sym (fn [sym]
                      (if-let [resolved (get aliases (symbol (namespace sym)))]
                        (symbol (str resolved) (name sym))
                        sym))]
    `(~'bidi.bidi/tag
      (delay
       ~(u/update-some endpoint (if (:ns &env)
                                  {:view (fn [v] `(lazy/loadable ~(resolve-sym (second v))))}
                                  {:query (fn [s] `(requiring-resolve ~s))
                                   :mutation (fn [s] `(requiring-resolve ~s))
                                   :handler (fn [s] `(requiring-resolve ~s))})))
      ~tag)))

(defmacro defview [name & args]
  (v/defview:impl
   {:wrap-expr (fn [expr] `(re-db.react/use-derefs ~expr))}
   name
   args))