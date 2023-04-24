(ns sparkboard.client.scratch
  (:require [yawn.view :as v]))

(v/x [:div (v/props {:A 1} {})])

(prn (macroexpand '(v/props {:a 1} foo)))