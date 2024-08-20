(ns sb.app.discussion.data
  (:require [re-db.api :as db]
            [sb.authorize :as az]
            [sb.schema :as sch :refer [s- ?]]
            [sb.query :as q]
            [sb.server.datalevin :as dl]
            [sb.validate :as validate]
            [re-db.schema :as rs]))

(sch/register!
  (merge
    {:post/_parent       {s- [:sequential :post/as-map]}
     :post/parent        (sch/ref :one)
     :post/text          {s-           :prose/as-map
                          :db/fulltext false}
     :post/do-not-follow (merge
                           {:doc "Members who should not auto-follow this post after replying to it"}
                           (sch/ref :many))
     :post/followers     (merge {:doc "Members who should be notified upon new replies to this post"}
                                (sch/ref :many))
     :post/as-map        {s- [:map {:closed true}
                              :entity/id
                              :entity/kind
                              :post/parent
                              :post/text
                              :entity/created-by
                              :entity/created-at
                              (? :post/do-not-follow)
                              (? :post/followers)]}}
    {:discussion/followers (sch/ref :many)}))

(def posts-field
  {:post/_parent
   [:entity/id
    :entity/kind
    :post/text
    {:entity/created-by [:entity/id
                         :entity/kind
                         :account/display-name
                         {:image/avatar [:entity/id]}]}
    :entity/created-at]})

(def posts-with-comments-field
  (update posts-field :post/_parent conj posts-field))

(q/defx new-post!
  {:prepare [az/with-account-id!]}
  [{:keys [post account-id]}]
  (let [post (-> (dl/new-entity post :post :by account-id)
                 (validate/assert :post/as-map))]
    (db/transact! [post])
    {:entity/id (:entity/id post)}))
