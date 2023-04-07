(ns electric.demo-toggle
  (:require [hyperfiddle.electric :as e]
            [hyperfiddle.electric-dom2 :as dom :refer [div text]]
            [hyperfiddle.electric-ui4 :as ui]))

#?(:clj (defonce !x (atom true))) ; server state
(e/def x (e/server (e/watch !x))) ; reactive signal derived from atom

(e/defn Toggle []
  (e/client
   (div (text "number type here is: "
              (case x
                true (e/client (pr-str (type 1))) ; javascript number type
                false (e/server (pr-str (type 1)))))) ; java number type

   (div (text "current site: "
              (if x
                "ClojureScript (client)"
                "Clojure (server)")))

   (ui/button
    (e/fn []
          (e/server (swap! !x not)))
    (text "toggle client/server"))))