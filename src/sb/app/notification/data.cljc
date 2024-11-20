(ns sb.app.notification.data
  (:require [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [s- ?]]
            [sb.server.datalevin :as dl]
            [sb.util :as u]))

(def notification-keys
  [(? :notification/recipients)
   (? :notification/unread-by)
   (? :notification/email-to)])

(sch/register!
 {:notification/recipients            (sch/ref :many),
  :notification/unread-by             (sch/ref :many)
  :notification/email-to              (sch/ref :many)
  :notification/as-map                {s- (into [:map {:closed true}
                                                 :entity/id
                                                 (? :entity/created-at)]
                                                notification-keys)}})

(def get-project (some-fn :membership/entity (partial u/auto-reduce :post/parent)))

(defn assoc-recipients [entity recipients]
  (let [;; validation fails for sets
        recipients (vec recipients)]
    (assoc entity
           :notification/recipients recipients
           :notification/unread-by recipients
           :notification/email-to recipients)))

(q/defquery all
  {:prepare [az/with-account-id]}
  [{:keys [account-id]}]
  (->> (db/where [[:notification/recipients account-id]])
       (mapv (comp (fn [notification]
                     (assoc notification
                            :notification/viewed? (->> (db/entity (sch/wrap-id notification))
                                                       :notification/unread-by
                                                       (some (partial sch/id= account-id))
                                                       not)
                            :notification/profile
                            (db/pull '[:entity/id :entity/kind
                                       {:membership/entity [:entity/id :entity/member-tags]}
                                       {:entity/tags [:entity/id
                                                      :tag/label
                                                      :tag/color]}
                                       {:membership/member [:entity/id]}]
                                     (az/membership-id (or (:membership/member notification)
                                                           (:entity/created-by notification))
                                                       (:entity/parent (get-project notification))))))
                   (db/pull `[:entity/id
                             :entity/kind
                             :entity/created-at
                              :membership/member-approval-pending?
                             {:membership/member ~entity.data/listing-fields}
                              {:membership/entity [~@entity.data/listing-fields
                                                   {:entity/parent [:entity/id]}]}
                              :post/text
                              {:entity/created-by ~entity.data/listing-fields}
                              {:post/parent [:entity/id
                                             :entity/kind
                                             :entity/title
                                             :post/text
                                             {:entity/parent [:entity/id]}
                                             {:post/parent [~@entity.data/listing-fields
                                                            {:entity/parent [:entity/id]}]}]}])))))

(q/defquery counts
  {:prepare [az/with-account-id!]}
  [params]
  (let [notifications @(q/$ all params)]
    {:total  (count notifications)
     :unread (->> notifications
                  (filter (complement :notification/viewed?))
                  count)}))
