(ns sb.app.views.ui
  (:require [sb.util :as u]
            [yawn.view :as v]
            [inside-out.macros]))

(defmacro defview [name & args]
  (let [[name doc options argv body] (u/parse-defn-args name args)
        args (concat (keep identity [doc options argv])
                     body)
        options (cond-> options
                        (:route options)
                        (assoc-in [:endpoint :view] (:route options)))]
    (if (:ns &env)                                          ;; we are compiling for clojurescript
      (do
        (when-let [the-var (resolve (symbol (str *ns*) (str name)))]
          (alter-meta! the-var merge options))

        `(do ~(v/defview:impl
                {:wrap-expr (fn [expr] `(~'re-db.react/use-derefs ~expr))}
                name
                args)
             ~(when (:route options)
                `(sb.routing/register-route ~name ~options))))
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

(defmacro boundary [{:keys [on-error]} & body]
  `(let [on-error# ~on-error]
     (~'try
       (~'sb.app.views.ui/error-boundary
         on-error#
         ~@body)
       (~'catch ~'js/Error e#
         (on-error# e#)))))

(defmacro transition [expr]
  `(~'sb.app.views.ui/startTransition (fn [] ~expr)))

(defmacro with-let
  "Within a reaction, evaluates bindings once, memoizing the results."
  [bindings & body]
  (let [finally (some-> (last body)
                        (u/guard #(and (list? %) (= 'finally (first %))))
                        rest)
        body    (cond-> body finally drop-last)]
    `(let [~@(mapcat (fn [[sym value]]
                       [sym `(~'yawn.hooks/use-memo (fn [] ~value))])
                     (partition 2 bindings))]
       ~(when (seq finally)
          `(~'yawn.hooks/use-effect (fn [] (fn [] ~@finally))))
       ~@body)))