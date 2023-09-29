(ns sparkboard.app.vote
  (:require [sparkboard.schema :as sch :refer [s-]]))

(sch/register!
  {:member-vote/open?            {:doc "Opens a community vote (shown as a tab on the board)"
                                  s-   :boolean}
   :ballot/as-map                {s- [:map {:closed true}
                                      :ballot/board
                                      :ballot/account
                                      :ballot/project]}
   :ballot/board                 (sch/ref :one)
   :ballot/account+board+project (merge {:db/tupleAttrs [:ballot/account :ballot/board :ballot/project]}
                                        sch/unique-id)
   :ballot/account               (sch/ref :one)
   :ballot/project               (sch/ref :one)})