(ns sparkboard.app.vote.data
  (:require [sparkboard.schema :as sch :refer [s-]]))

(sch/register!
  {:member-vote/open? {:doc "Opens a community vote (shown as a tab on the board)"
                       s-   :boolean}
   :ballot/as-map     {s- [:map {:closed true}
                           :ballot/key
                           :ballot/board
                           :ballot/account
                           :ballot/project]}
   :ballot/key        (merge {:doc "ballot/board + ballot/account + ballot/project"}
                             sch/unique-id-str)
   :ballot/board      (sch/ref :one)
   :ballot/account    (sch/ref :one)
   :ballot/project    (sch/ref :one)})