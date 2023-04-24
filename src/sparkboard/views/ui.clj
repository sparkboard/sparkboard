(ns sparkboard.views.ui
  (:require [yawn.view :as v]
            [inside-out.macros]))

(defmacro defview [name & args]
  (v/defview:impl
   {:wrap-expr (fn [expr] `(~'re-db.react/use-derefs ~expr))}
   name
   args))

(defmacro with-form [bindings & body]
  (inside-out.macros/with-form* &form &env {} bindings [`(v/x (do ~@body))]))