(ns sparkboard.views.ui
  (:require [yawn.view :as v]
            [re-db.react])
  #?(:cljs (:require-macros sparkboard.views.ui)))

(defmacro defview [name & args]
  (v/defview:impl
    {:wrap-expr (fn [expr] `(re-db.react/use-derefs ~expr))}
   name
   args))