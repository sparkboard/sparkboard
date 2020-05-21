(ns server.slack.screens
  (:require [applied-science.js-interop :as j]
            [server.blocks :as blocks]
            [server.common :as common]
            [server.slack :as slack]))

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
     [:button {:url (slack/only-install-link
                      team-id
                      (common/lambda-root-url req))} "Reinstall App"]]))

(defn home [props]
  [:home
   (main-menu props)
   [:section
    (str "_Last updated:_ "
         (-> (js/Date.)
             (.toLocaleString "en-US" #js{:dateStyle "medium"
                                          :timeStyle "medium"})))]])

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

(def team-broadcast-modal-compose
  [:modal {:title [:plain_text "Compose Broadcast"]
           :blocks team-broadcast-blocks
           :submit [:plain_text "Submit"]}])

(defn team-broadcast-message [msg]
  (list
    [:section {:text {:type "mrkdwn" :text msg}}]
    {:type "actions",
     :elements [[:button {:style "primary"
                          :text {:type "plain_text",
                                 :text "Post an Update",
                                 :emoji true},
                          :action_id "user:team-broadcast-response"
                          :value "click_me_123"}]]}))

(def team-broadcast-response
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
           :submit [:plain_text "Send"]}])

(def team-broadcast-response-status
  [:modal {:title [:plain_text "Describe Current Status"]
           :blocks [{:type "input",
                     :label {:type "plain_text",
                             :text "Tell us what you've been working on:",
                             :emoji true},
                     :element {:type "plain_text_input", :multiline true}}]
           :submit [:plain_text "Send"]}])

(def team-broadcast-response-achievement
  [:modal {:title [:plain_text "Share Achievement"]
           :blocks [{:type "input",
                     :label {:type "plain_text",
                             :text "Tell us about the milestone you reached:",
                             :emoji true},
                     :element {:type "plain_text_input", :multiline true}}]
           :submit [:plain_text "Send"]}])

(def team-broadcast-response-help
  [:modal {:title [:plain_text "Request for Help"]
           :blocks [{:type "input",
                     :label {:type "plain_text",
                             :text "Let us know what you could use help with. We'll try to lend a hand.",
                             :emoji true},
                     :element {:type "plain_text_input", :multiline true}}]
           :submit [:plain_text "Send"]}])

(def team-broadcast-response-msg
  [{:type "divider"}
   {:type "section",
    :text {:type "mrkdwn", :text "_Project:_ *Diabetes Monitor*"}} ; FIXME
   {:type "section",
    :text {:type "plain_text",
                                        ; FIXME
           :text "Our team has finished step 1 of the prototype and we are now starting on ...blah...",
           :emoji true}}
   {:type "actions",
    :elements [{:type "button",
                :text {:type "plain_text",
                       :text "Go to channel", ; FIXME
                       :emoji true},
                :value "click_me_123"}
               {:type "button",
                :text {:type "plain_text",
                       :text "View project",
                       :emoji true}
                :value "click_me_123"}]}])

(comment
  (blocks/parse team-broadcast-modal-compose)
  (blocks/parse [:md "hi"]))
