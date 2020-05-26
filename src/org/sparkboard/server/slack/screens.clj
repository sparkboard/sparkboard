(ns org.sparkboard.server.slack.screens
  ;; (:require [server.slack.hiccup :as hiccup]
  ;;           [server.common :as common]
  ;;           [org.sparkboard.slack.slack-db :as slack-db])
  )

(defn main-menu [{:as props :keys [lambda/req slack/team-id]}]
  (list
    [:section
     {:accessory [:button {:style "primary",
                           :action_id "admin:team-broadcast"
                           :value "click_me_123"}
                  "Compose"]}
     "*Team Broadcast*\nSend a message to all teams."]
    [:divider]
    [:section "Admin actions"]
    [:actions
     [:button {:url "TODO" #_(slack-db/get-install-link
                      {:slack/team-id team-id
                       :lambda/root (common/lambda-root-url req)})} "Reinstall App"]]))

(defn home [props]
  [:home
   (main-menu props)
   [:section
    (str "_Last updated:_ "
         ;; FIXME (-> (js/Date.)
         ;;     (.toLocaleString "en-US" #js{:dateStyle "medium"
         ;;                                  :timeStyle "medium"}))
         )]])

(defn shortcut-modal [props]
  [:modal {:title "Broadcast"
           :blocks (main-menu props)}])

(def team-broadcast-blocks
  (list
    [:section "Send a prompt to *all projects*."]
    [:divider]
    [:section
     {:block_id "sb-section1"
      :accessory [:conversations_select
                  {:placeholder [:plain_text "Select a channel..."],
                   :initial_conversation "team-updates" ; default channel TODO should this come from db?
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
  ([] (team-broadcast-modal-compose nil))
  ([private-data]
   [:modal (merge {:title [:plain_text "Compose Broadcast"]
                   :blocks team-broadcast-blocks
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

(defn team-broadcast-response-achievement [private-metadata]
  [:modal {:title [:plain_text "Share Achievement"]
           :blocks [{:type "input",
                     :label {:type "plain_text",
                             :text "Tell us about the milestone you reached:",
                             :emoji true},
                     :block_id "sb-project-achievement1"
                     :element {:type "plain_text_input", :multiline true
                               :action_id "user:achievement-input"}}]
           :private_metadata private-metadata
           :submit [:plain_text "Send"]}])

(defn team-broadcast-response-help [private-metadata]
  [:modal {:title [:plain_text "Request for Help"]
           :blocks [{:type "input",
                     :label {:type "plain_text",
                             :text "Let us know what you could use help with. We'll try to lend a hand.",
                             :emoji true},
                     :block_id "sb-project-help1"
                     :element {:type "plain_text_input", :multiline true
                               :action_id "user:help-input"}}]
           :private_metadata private-metadata
           :submit [:plain_text "Send"]}])

(defn team-broadcast-response-msg [project msg]
  [{:type "divider"}
   {:type "section",
    :text {:type "mrkdwn", :text (str "_Project:_ * " project "*")}}
   {:type "section",
    :text {:type "plain_text", :text msg, :emoji true}}
   #_ {:type "actions",
    :elements [{:type "button",
                :text {:type "plain_text",
                       :text "Go to channel", ; FIXME (project->channel project)
                       :emoji true},
                :value "click_me_123"}
               {:type "button",
                :text {:type "plain_text",
                       :text "View project", ; FIXME sparkboard project URL
                       :emoji true}
                :value "click_me_123"}]}])

(comment
  (hiccup/->blocks team-broadcast-modal-compose)
  (hiccup/->blocks [:md "hi"]))
