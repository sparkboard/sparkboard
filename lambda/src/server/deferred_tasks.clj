(ns server.deferred-tasks)

(defmacro register-handler! [sym]
  {:pre [(and (list? sym) (= 'quote (first sym)))]}
  `(~'server.deferred-tasks/register-handler* ~sym ~(second sym)))