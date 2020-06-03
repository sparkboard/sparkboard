(ns org.sparkboard.server.slack.screens
  (:require [org.sparkboard.slack.urls :as urls]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.server.slack.core :as slack]
            [org.sparkboard.server.slack.hiccup :as hiccup]))

(defn main-menu [context]
  (list
    (let [{:keys [title domain]} (fire-jvm/read (str "settings/" (:sparkboard/board-id context)))]
      [:section
       {:accessory [:button {:url (urls/sparkboard-host domain)} "Visit Board"]}
       (str "This Slack team is connected to *" title "* on Sparkboard.")])
    [:divider]
    [:section
     {:accessory [:button {:style "primary",
                           :action_id "admin:team-broadcast"
                           :value "click_me_123"}
                  "Compose"]}
     "*Team Broadcast*\nSend a message to all teams."]))

(defn link-account [context]
  (let [linking-url (urls/link-sparkboard-account context)]
    (list
      [:section (str "Please link your Sparkboard account:")]
      [:actions
       [:button {:style "primary"
                 :action_id "URL"
                 :url linking-url}
        (str "Link Account")]])))

(defn home [context]
  [:home
   (cond (nil? (:sparkboard/board-id context))
         [:section "No Sparkboard is linked to this Slack workspace."]
         (nil? (:sparkboard/account-id context)) (link-account context)
         :else (main-menu context))
   [:section
    (str "_Last updated: "
         (->> (java.util.Date.)
              (.format (new java.text.SimpleDateFormat "hh:mm:ss a, MMMM d, YYYY"))) "_")]
   [:actions
    [:button {:url (urls/install-slack-app (select-keys context [:sparkboard/jvm-root
                                                                 :slack/team-id]))} "Reinstall App"]]])

(defn shortcut-modal [context]
  [:modal {:title "Broadcast"
           :blocks (main-menu context)}])

(defn destination-channel-groups [{:keys [slack/bot-token
                                    slack/bot-user-id]}]
  (let [channels (->> (slack/web-api "conversations.list"
                                     {:auth/token bot-token}
                                     {:exclude_archived true
                                      :types "public_channel,private_channel"})
                      :channels
                      (filter :is_member))
        {general false
         project true} (group-by #(= bot-user-id (-> % :topic :creator)) channels)
        channel-option (fn [{:keys [name id]}]
                         {:value id
                          :text [:plain_text name]})]
    [{:options (mapv channel-option general)
      :label [:plain_text "User channels"]}
     {:options (mapv channel-option project)
      :label [:plain_text "Projects"]}]))

(comment
  ;; TODO
  ; replace conversations_select below with sth like this (currently doesn't work as-is)
  [:static_select
   {:placeholder [:plain_text "Select a channel..."],
    :option_groups (destination-channel-groups context)
    :action_id "broadcast2:channel-select"}])

(defn team-broadcast-blocks [context]
  (list
    [:section "Send a prompt to *all project teams*."]
    [:divider]
    [:section
     {:block_id "sb-section1"
      :accessory [:conversations_select
                  {:placeholder [:plain_text "Select a channel..."],
                   :initial_conversation "team-updates"     ; default channel TODO should this come from db?
                   :action_id "broadcast2:channel-select"
                   :filter {:include ["public" "private"]}}]}
     "*Post responses to channel:*"]
    [:input
     {:block_id "sb-input1"
      :element [:plain_text_input
                {:multiline true,
                 :action_id "broadcast2:text-input"
                 :initial_value "It's 2 o'clock! Please post a brief update of your team's progress so far today."}],
      :label [:plain_text "Message:"]}]))

(defn team-broadcast-modal-compose
  ([context] (team-broadcast-modal-compose context nil))
  ([context private-data]
   [:modal (merge {:title [:plain_text "Compose Broadcast"]
                   :blocks (team-broadcast-blocks context)
                   :submit [:plain_text "Submit"]}
                  ;; NB: private metadata is a String of max 3000 chars
                  ;; See https://api.slack.com/reference/surfaces/views
                  (when private-data {:private_metadata private-data}))]))

(defn team-broadcast-message [msg reply-channel]
  (list
    [:section {:text {:type "mrkdwn" :text msg}}]
    {:type "actions",
     :elements [[:button {:style "primary"
                          :text {:type "plain_text",
                                 :text "Post an Update",
                                 :emoji true},
                          :action_id "user:team-broadcast-response"
                          :value "click_me_123"}]]}
    {:type "context",
     :elements [{:type "mrkdwn",
                 ;; TODO make `reply-channel` a human-readable channel
                 ;; name; `db` namespace was broken in an earlier
                 ;; refactor so don't have time now
                 :text (str "Responses will post to channel [" reply-channel "]")}]}))

(defn team-broadcast-response [reply-channel]
  [:modal {:title [:plain_text "Project Update"]
           :blocks (list
                     {:type "actions",
                      :elements [[:button {:text {:type "plain_text",
                                                  :text "Describe current status",
                                                  :emoji true},
                                           :action_id "user:team-broadcast-response-status"
                                           :value "click_me_123"}]
                                 [:button {:text {:type "plain_text",
                                                  :text "Share achievement",
                                                  :emoji true},
                                           :action_id "user:team-broadcast-response-achievement"
                                           :value "click_me_456"}]
                                 [:button {:text {:type "plain_text",
                                                  :text "Ask for help",
                                                  :emoji true},
                                           :action_id "user:team-broadcast-response-help"
                                           :value "click_me_789"}]]})
           :submit [:plain_text "Send"]
           :private_metadata reply-channel}])

(defn team-broadcast-response-status [private-metadata]
  [:modal {:title [:plain_text "Describe Current Status"]
           :blocks [{:type "input",
                     :label {:type "plain_text",
                             :text "Tell us what you've been working on:",
                             :emoji true},
                     :block_id "sb-project-status1"
                     :element {:type "plain_text_input", :multiline true
                               :action_id "user:status-input"}}]
           :private_metadata private-metadata
           :submit [:plain_text "Send"]}])

(defn team-broadcast-response-msg [project msg]
  [{:type "divider"}
   {:type "section",
    :text {:type "mrkdwn", :text (str "_Project:_ * " project "*")}}
   {:type "section",
    :text {:type "plain_text", :text msg, :emoji true}}
   #_{:type "actions",
      :elements [{:type "button",
                  :text {:type "plain_text",
                         :text "Go to channel",             ; FIXME (project->channel project)
                         :emoji true},
                  :value "click_me_123"}
                 {:type "button",
                  :text {:type "plain_text",
                         :text "View project",              ; FIXME sparkboard project URL
                         :emoji true}
                  :value "click_me_123"}]}])

(comment
  (hiccup/->blocks team-broadcast-modal-compose)
  (hiccup/->blocks [:md "hi"]))
