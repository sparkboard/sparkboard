(ns server.slack.screens)

(def home
  [:section
   {:accessory [:button {:style "primary",
                         :action_id "admin:team-broadcast"
                         :value "click_me_123"}
                "Compose"]}
   "*Team Broadcast*\nSend a message to all teams."])

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
