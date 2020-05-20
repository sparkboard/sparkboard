(ns server.deferred-tasks)

(defmacro register-var! [sym]
  {:pre [(and (list? sym) (= 'quote (first sym)))]}
  `(~'server.deferred-tasks/alias* ~sym ~(second sym)))