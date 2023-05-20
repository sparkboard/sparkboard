(ns sparkboard.server.query
  "Database queries and mutations (transactions)"
  (:require [re-db.memo :as memo]
            [re-db.reactive :as r])
  #?(:cljs (:require-macros sparkboard.server.query)))

;; TODO
;; defquery can specify an out-of-band authorization fn that
;; can pass/fail the subscription based on the session without
;; including the session in the subscription args
(defmacro reactive
  "Defines a query function. The function will be memoized and return a {:value / :error} map."
  [name & fn-body]
  (if (:ns &env)
    ``~name
    (let [[doc [argv & body]] (if (string? (first fn-body))
                                [(first fn-body) (rest fn-body)]
                                [nil fn-body])]
      `(memo/defn-memo ~name ~argv
         (r/reaction ~@body)))))

(defmacro static
  [name & fn-body]
  (if (:ns &env)
    ``~name
    `(defn ~name ~@fn-body)))