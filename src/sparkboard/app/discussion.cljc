(ns sparkboard.app.discussion
  (:require [sparkboard.schema :as sch :refer [s- ?]]
            [re-db.schema :as rs]))

(sch/register!
  (merge

    {:post/_discussion   {s- [:sequential :post/as-map]}
     :post/comments      (merge (sch/ref :many :comment/as-map)
                                sch/component)
     :post/text          {s-           :prose/as-map
                          :db/fulltext false}
     :post/do-not-follow (merge
                           {:doc "Members who should not auto-follow this post after replying to it"}
                           (sch/ref :many))
     :post/followers     (merge {:doc "Members who should be notified upon new replies to this post"}
                                (sch/ref :many))
     :comment/text       {s- :string}
     :post/as-map        {s- [:map {:closed true}
                              :entity/id
                              :post/text
                              :entity/created-by
                              :entity/created-at
                              (? :post/comments)
                              (? :post/do-not-follow)
                              (? :post/followers)]}
     :comment/as-map     {s- [:map {:closed true}
                              :entity/id
                              :entity/created-at
                              :entity/created-by
                              :comment/text]}}

    {:discussion/followers (sch/ref :many),
     :discussion/posts     (merge (sch/ref :many :post/as-map)
                                  rs/component)
     :discussion/project   (sch/ref :one)
     :discussion/as-map    {s- [:map {:closed true}
                                :entity/id
                                :discussion/project
                                :entity/created-at
                                (? :discussion/followers)
                                (? :discussion/posts)]}}))