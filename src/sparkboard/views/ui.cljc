(ns sparkboard.views.ui
  (:require [inside-out.macros]
            [re-db.react]
            [yawn.view :as v])
  #?(:cljs (:require-macros sparkboard.views.ui)))

(defmacro defview [name & args]
  (v/defview:impl
   {:wrap-expr (fn [expr] `(re-db.react/use-derefs ~expr))}
   name
   args))

(defmacro with-form [bindings & body]
  (inside-out.macros/with-form* &form &env {} bindings [`(v/x ~@body)]))

(def button:dark
  (v/from-element :button
    {:class
     ["inline-flex items-center justify-center "
      " text-sm font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none "
      "ring-offset-background bg-primary text-primary-foreground hover:bg-primary/90 "
      "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 "
      "rounded-md h-10 py-2 px-4"]}))