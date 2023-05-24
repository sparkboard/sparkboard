(ns sparkboard.views.ui
  (:require [yawn.view :as v]
            [clojure.walk :as walk]
            [inside-out.macros]))

(defn wrap-tr [expr]
  (walk/postwalk (fn [x] (if (and (keyword? x)
                                  (= "tr" (namespace x)))
                           `(~'sparkboard.i18n/tr ~x)
                           x))
                 expr))

(defmacro tr [expr]
  (wrap-tr expr))

(defmacro defview [name & args]
  (if (:ns &env)
    (v/defview:impl
      {:wrap-expr (fn [expr] `(~'re-db.react/use-derefs (tr ~expr)))}
      name
      args)
    ``~name))

(defmacro with-submission [bindings & body]
  (let [binding-map (apply hash-map bindings)
        ?form (:form binding-map)
        [result promise] (first (dissoc binding-map :form))]
    (assert ?form "with-submission requires a :form")
    (assert (= 4 (count bindings))
            "with-submission requires exactly 2 bindings, [result (...promise) :form !form]")
    `(~'promesa.core/let [result# (~'inside-out.forms/try-submit+ ~?form
                                    ~promise)]
       (when-not (:error result#)
         (let [~result result#]
           ~@body)))))

(defmacro x [& args]
  (let [args (wrap-tr args)]
    `(do ~@(butlast args)
         (~'yawn.view/x ~(last args)))))

(defmacro with-form [bindings & body]
  (inside-out.macros/with-form* &form &env {} bindings [`(v/x (do ~@body))]))