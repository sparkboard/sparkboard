(ns sparkboard.app.chat
  (:require [sparkboard.schema :as sch :refer [s-]]))

(sch/register!
  {:chat/members        (merge (sch/ref :many)
                               {:doc "Set of participants in a chat."}),
   :chat.message/id     sch/unique-string-id
   :chat.message/text   {s- :string}
   :chat/messages       {:doc      "List of messages in a chat.",
                         :order-by :entity/created-at
                         s-        [:sequential :chat.message/as-map]},
   :chat/read-by        (merge
                          (sch/ref :many)
                          {:doc  "Set of members who have read the most recent message.",
                           :todo "Map of {member, last-read-message} so that we can show unread messages for each member."})
   :chat/as-map         {s- [:map {:closed true}
                             :entity/id
                             :entity/created-at
                             :entity/updated-at
                             :chat/members
                             :chat/messages
                             :chat/read-by]},
   :chat.message/as-map {s- [:map {:closed true}
                             :entity/id
                             :entity/created-by
                             :entity/created-at
                             :chat.message/text]}})