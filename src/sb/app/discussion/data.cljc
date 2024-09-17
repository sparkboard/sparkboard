(ns sb.app.discussion.data
  (:require [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
            [sb.app.notification.data :as notification.data]
            [sb.authorize :as az]
            [sb.schema :as sch :refer [s- ?]]
            [sb.query :as q]
            [sb.server.datalevin :as dl]
            [sb.validate :as validate]
            [sb.util :as u]
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
     :post/as-map        {s- (into [:map {:closed true}
                                    :entity/id
                                    :entity/kind
                                    :post/parent
                                    :post/text
                                    :entity/created-by
                                    :entity/created-at
                                    (? :post/do-not-follow)
                                    (? :post/followers)]
                                   notification.data/notification-keys)}}))

(def posts-field
  `{:post/_parent
    [:entity/id
     :entity/kind
     :post/text
     {:entity/created-by ~entity.data/listing-fields}
     :entity/created-at]})

(def posts-with-comments-field
  (update posts-field :post/_parent conj posts-field))

(defn followers [entity-id]
  (let [entity (db/entity entity-id)
        explicit (:post/followers entity)
        creator (:entity/created-by entity)
        project-admins (db/where [[:membership/entity entity-id]
                                  (comp :role/project-admin :membership/roles)])
        do-not-follow (:post/do-not-follow entity)]
    (-> #{creator}
        (into explicit)
        (into project-admins)
        ((partial apply disj) do-not-follow)
        (->> (into #{} (map sch/wrap-id))))))

(q/defquery follows?
  {:prepare [az/with-account-id!]}
  [{:keys [entity-id account-id]}]
  (let [this-account? (partial sch/id= account-id)
        entity (db/entity entity-id)]
    (when-not (some this-account? (:post/do-not-follow entity))
      (or (this-account? (:entity/created-by entity))
          (some this-account? (:post/followers entity))
          (some this-account? (db/where [[:membership/entity entity-id]
                                         (comp :role/project-admin :membership/roles)]))))))

(q/defx set-follow!
   {:prepare [az/with-account-id!]}
  [{:keys [entity-id account-id] :as params} follow?]
  (db/transact! (if follow?
                  [[:db/add entity-id :post/followers account-id]
                   [:db/retract entity-id :post/do-not-follow account-id]]
                  [[:db/add entity-id :post/do-not-follow account-id]
                   [:db/retract entity-id :post/followers account-id]]))
  nil)

(q/defx new-post!
  {:prepare [az/with-account-id!]}
  [{:keys [post account-id]}]
  (let [parent (dl/entity (:post/parent post))
        post (-> post
                 (notification.data/assoc-recipients (-> (followers (:post/parent post))
                                                         (disj account-id)))
                 (dl/new-entity :post :by account-id)
                 (validate/assert :post/as-map))]
    (db/transact! [post
                   (when-not (some (partial sch/id= account-id)
                                   (:post/do-not-follow parent))
                     [:db/add (:post/parent post) :post/followers account-id])])
    {:entity/id (:entity/id post)}))
