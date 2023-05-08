(ns sparkboard.views.ui
  (:require [yawn.view :as v]
            [clojure.walk :as walk]
            [inside-out.macros]))

(defmacro defview [name & args]
  (v/defview:impl
   {:wrap-expr (fn [expr] `(~'re-db.react/use-derefs ~(walk/postwalk (fn [x] (if (and (keyword? x)
                                                                                      (= "tr" (namespace x)))
                                                                               `(~'sparkboard.i18n/tr ~x)
                                                                               x))
                                                                     expr)))}
   name
   args))

(defmacro with-form [bindings & body]
  (inside-out.macros/with-form* &form &env {} bindings [`(v/x (do ~@body))]))