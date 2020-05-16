(ns server.slack-events
  (:require [cljs.pprint :as pp]
            [server.blocks :as blocks]
            [server.slack :as slack]))

(defn handle! [{:as event
                event-type :type
                :keys [user channel tab]}]
  (pp/pprint [::event event])
  (case event-type
    "app_home_opened"
    (slack/post-query-string+ "views.publish"
                              {:user_id user
                               :view
                               (blocks/to-json
                                 [:home
                                  [:section "Hello there, _friend_!."]])})))