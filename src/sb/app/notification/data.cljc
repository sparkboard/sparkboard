(ns sb.app.notification.data
  (:require [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
            [sb.app.time :as time]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [s- ?]]
            [sb.server.datalevin :as dl]
            [sb.validate :as validate]
            [sb.util :as u]
            [net.cgrand.xforms :as xf]))

(sch/register!
 {:notification/subject               (merge
                                       (sch/ref :one)
                                       {:doc "The primary entity referred to in the notification (when viewed"}),
  :notification/type                  {s- [:enum
                                           :notification.type/new-member
                                           :notification.type/new-invitation
                                           :notification.type/new-post]}
  :notification/recipients            (sch/ref :many),
  :notification/unread-by             (sch/ref :many)
  :notification/email-to              (sch/ref :many)
  :notification/as-map                {s- [:map {:closed true}
                                           :entity/id
                                           :entity/kind
                                           :notification/type
                                           :notification/subject
                                           :notification/recipients
                                           (? :notification/unread-by)
                                           (? :notification/email-to)
                                           :entity/created-at]}})

(def get-context (some-fn
                  ;; `:notification`
                  (comp (some-fn :membership/entity (partial u/auto-reduce :post/parent))
                        :notification/subject)
                  ;; `:chat.message`
                  :entity/created-by))

(defn sort-and-group [notifications]
  (into []
        (comp (xf/sort-by #(.getTime (:entity/created-at %)) >)
              (partition-by (comp time/small-datestamp :entity/created-at))
              (map (fn [notifications-on-day]
                     {:first-notification-at (:entity/created-at (first notifications-on-day))
                      :notifications
                      (into []
                            (comp (partition-by (comp :entity/id get-context))
                                  (map (fn [notifications]
                                         {:context (get-context (peek notifications))
                                          :notifications notifications})))
                            notifications-on-day)})))
        notifications))

#?(:clj
   (defn new [ntype subject recipients]
     (let [;; validation fails for sets
           recipients (vec recipients)]
       (-> (dl/new-entity {:notification/type ntype
                           :notification/subject subject
                           :notification/recipients recipients
                           :notification/unread-by recipients
                           :notification/email-to recipients}
                          :notification)
           (validate/assert :notification/as-map)))))

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
                            (some->> (:entity/parent (get-context notification))
                                     (az/membership-id (or (:membership/member (:notification/subject notification))
                                                           (:entity/created-by (:notification/subject notification))))
                                     (db/pull '[:entity/id :entity/kind
                                                {:membership/entity [:entity/id :entity/member-tags]}
                                                {:entity/tags [:entity/id
                                                               :tag/label
                                                               :tag/color]}
                                                {:membership/member [:entity/id]}]))))
                   (db/pull `[:entity/id
                              :entity/kind
                              :entity/created-at
                              :notification/type
                              {:notification/subject [:entity/id
                                                      :entity/kind
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
                                                                                    {:entity/parent [:entity/id]}]}]}]}])))))

(q/defquery counts
  {:prepare [az/with-account-id!]}
  [params]
  (let [notifications @(q/$ all params)]
    {:total  (count notifications)
     :unread (->> notifications
                  (filter (complement :notification/viewed?))
                  count)}))

(q/defx set-as-read!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id notification-id]}]
  (db/transact! [[:db/retract notification-id :notification/unread-by account-id]
                 [:db/retract notification-id :notification/email-to account-id]])
  {:body ""})
