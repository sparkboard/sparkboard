(ns sparkboard.app.notification
  (:require [sparkboard.schema :as sch :refer [s- ?]]))

(sch/register!
  {:notification/comment               (sch/ref :one),
   :notification/discussion            (sch/ref :one)
   :notification/emailed?              {:doc  "The notification has been included in an email",
                                    :todo "deprecate: log {:notifications/emailed-at _} per member"
                                    s-    :boolean},
   :notification/account               (sch/ref :one)
   :notification/post                  (sch/ref :one)
   :notification/post.comment          (sch/ref :one)
   :notification/project               (sch/ref :one)
   :notification/recipient             (sch/ref :one),
   :notification/subject               (merge
                                     (sch/ref :one)
                                     {:doc "The primary entity referred to in the notification (when viewed"}),
   :notification/chat                  (sch/ref :one)
   :notification/viewed?               {:doc    "The notification is considered 'viewed' (can occur by viewing the notification or subject)",
                                    :unsure "Log log {:notifications/viewed-subject-at _} per [member, subject] pair, and {:notifications/viewed-notifications-at _}, instead?"
                                    s-      :boolean},
   :notification/chat.new-message.text {s- :string}
   :notification/type                  {s- [:enum
                                            :notification.type/project.new-member
                                            :notification.type/chat.new-message
                                            :notification.type/discussion.new-post
                                            :notification.type/discussion.new-comment]}
   :notification/as-map                {s- [:map {:closed true}
                                            :entity/id
                                            :notification/emailed?
                                            :notification/recipient
                                            :notification/type
                                            :notification/viewed?
                                            :entity/created-at
                                            (? :notification/discussion)
                                            (? :notification/account)
                                            (? :notification/post)
                                            (? :notification/post.comment)
                                            (? :notification/project)
                                            (? :notification/chat)
                                            (? :notification/chat.new-message.text)]}})