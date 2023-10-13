(ns sparkboard.ui
  (:refer-clojure :exclude [catch try])
  (:require [sparkboard.util :as u]
            [yawn.view :as v]
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
  (let [[name doc options argv body] (u/parse-defn-args name args)
        args (concat (keep identity [doc options argv])
                     body)]
    (if (:ns &env)
      `(do ~(v/defview:impl
              {:wrap-expr (fn [expr] `(~'re-db.react/use-derefs ~expr))}
              name
              args)
           ~(when (:route options)
              `(sparkboard.routes/register-route ~name {:route    ~(:route options)})))
      `(defn ~name ~@(when options [options])
         ~argv))))

(defmacro with-submission [bindings & body]
  (let [binding-map (apply hash-map bindings)
        ?form       (:form binding-map)
        [result promise] (first (dissoc binding-map :form))]
    (assert ?form "with-submission requires a :form")
    (assert (= 4 (count bindings))
            "with-submission requires exactly 2 bindings, [result (...promise) :form !form]")
    `(~'promesa.core/let [result# (~'inside-out.forms/try-submit+ ~?form
                                    ~promise)]
       (when-not (:error result#)
         (let [~result result#]
           ~@body)))))

(defmacro with-form [bindings & body]
  (inside-out.macros/with-form* &form &env {} bindings [`(v/x (do ~@body))]))

(defmacro try [& body]
  (let [[_ catch-type catch-e & catch-body :as catch-expr] (u/guard (last body) (comp #{'catch} first))
        catch-fn `(fn [~catch-e] ~@catch-body)
        body (cond-> body catch-expr drop-last)]
    `(let [catch-fn# ~catch-fn]
       (~'try
         (~'sparkboard.ui/error-boundary
           catch-fn#
           ~@body)
         (~'catch ~catch-type e#
           (when catch-fn# (catch-fn# e#)))))))

(defmacro transition [expr]
  `(~'sparkboard.ui/startTransition (fn [] ~expr)))