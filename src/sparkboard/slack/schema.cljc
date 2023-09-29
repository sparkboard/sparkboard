(ns sparkboard.slack.schema
  (:require [sparkboard.schema :as sch :refer [s- ?]]))

(sch/register!
  {:slack.app/bot-token                     {s- :string},
   :slack.app/bot-user-id                   {s- :string},
   :slack.app/id                            {s- :string}
   :slack.broadcast/id                      sch/unique-string-id
   :slack.broadcast/response-channel-id     (merge {:doc "Channel containing replies to a broadcast"
                                                    s-   :string}
                                                   sch/string),
   :slack.broadcast/response-thread-id      (merge {:doc "Thread containing replies to a broadcast"
                                                    s-   :string}
                                                   sch/string),
   :slack.broadcast/slack.team              (sch/ref :one)
   :slack.broadcast/text                    {s- :string}

   :slack.broadcast.reply/id                sch/unique-string-id
   :slack.broadcast.reply/text              {s- :string}
   :slack.broadcast.reply/channel-id        {:doc ""
                                             s-   :string}
   :slack.broadcast.reply/slack.user        (sch/ref :one)
   :slack.broadcast/slack.broadcast.replies (sch/ref :many :slack.broadcast.reply/as-map)
   :slack.broadcast/slack.user              (sch/ref :one)
   :slack.channel/id                        sch/unique-string-id
   :slack.channel/project                   (sch/ref :one),
   :slack.channel/slack.team                (sch/ref :one),
   :slack.team/board                        (merge (sch/ref :one)
                                                   {:doc "The sparkboard connected to this slack team"}),
   :slack.team/custom-messages              {s-   [:map {:closed true} :slack.team/custom-welcome-message],
                                             :doc "Custom messages for a Slack integration"},
   :slack.team/id                           sch/unique-string-id
   :slack.team/invite-link                  {s-   :string,
                                             :doc "Invitation link that allows a new user to sign up for a Slack team. (Typically expires every 30 days.)"},
   :slack.team/name                         {s- :string},
   :slack.team/slack.app                    {:doc "Slack app connected to this team (managed by Sparkboard)"
                                             s-   [:map {:closed true}
                                                   :slack.app/id
                                                   :slack.app/bot-user-id
                                                   :slack.app/bot-token]},
   :slack.team/custom-welcome-message       {s-   :string,
                                             :doc "A message sent to each user that joins the connected workspace (slack team). It should prompt the user to connect their account."},
   :slack.user/id                           sch/unique-string-id
   :slack.user/slack.team                   (sch/ref :one),
   :slack.user/firebase-account-id          {s- :string}

   :board/slack.team                        (merge (sch/ref :one :slack.team/as-map)
                                                   sch/component)
   :slack.broadcast/as-map                  {s- [:map {:closed true}
                                                 (? :slack.broadcast/response-channel-id)
                                                 (? :slack.broadcast/response-thread-id)
                                                 (? :slack.broadcast/slack.broadcast.replies)
                                                 (? :slack.broadcast/slack.team)
                                                 (? :slack.broadcast/slack.user)
                                                 :slack.broadcast/id
                                                 :slack.broadcast/text]},
   :slack.broadcast.reply/as-map            {s- [:map {:closed true}
                                                 :slack.broadcast.reply/id
                                                 :slack.broadcast.reply/text
                                                 :slack.broadcast.reply/slack.user
                                                 :slack.broadcast.reply/channel-id]}
   :slack.channel/as-map                    {s- [:map {:closed true}
                                                 :slack.channel/id
                                                 :slack.channel/project
                                                 :slack.channel/slack.team]},
   :slack.team/as-map                       {s- [:map {:closed true}
                                                 (? :slack.team/custom-messages)
                                                 (? :slack.team/invite-link)
                                                 :slack.team/board
                                                 :slack.team/id
                                                 :slack.team/name
                                                 :slack.team/slack.app]},
   :slack.user/as-map                       {s- [:map {:closed true}
                                                 :slack.user/id
                                                 :slack.user/firebase-account-id
                                                 :slack.user/slack.team]}})