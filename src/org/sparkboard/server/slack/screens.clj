(ns org.sparkboard.server.slack.screens
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.js-convert :refer [clj->json json->clj]]
            [org.sparkboard.server.slack.core :as slack]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.slack.urls :as urls]
            [org.sparkboard.slack.view :as view]
            [org.sparkboard.transit :as transit]
            [taoensso.timbre :as log]))

(defn channel-link [id]
  (str "<#" id ">"))

(defn mention [user-id]
  (str "<@" user-id "> "))

(def team-messages
  {:welcome
   {:label "Welcome message"
    :default "*Welcome here!* Please connect your Sparkboard account to continue:"}
   :welcome-confirmation
   {:label "Confirmation of account linking"
    :default (str "*Thanks!* You're ready to go. Projects on Sparkboard "
                  "automatically get a linked channel created here on Slack.")}})

(defn team-message [context k]
  (or (get-in context [:slack/team :custom-messages k])
      (get-in team-messages [k :default])))

(defn link-account [context]
  (let [linking-url (urls/link-sparkboard-account context)]
    (list
      [:section (str "Please link your Sparkboard account:")]
      [:actions
       [:button {:style "primary"
                 :action_id "URL"
                 :url linking-url}
        (str "Link Account")]])))

(defn admin-menu [context]
  (when (:is_admin (slack/user-info context))
    (list
      [:section "🛠 ADMIN ACTIONS\n"]
      [:divider]
      [:section
       {:accessory [:button {:style "primary"
                             :action_id "admin:team-broadcast"
                             :value "click_me_123"}
                    "Compose Broadcast"]}
       "*Team Broadcast:* send a message to all teams."]

      (let [{:keys [slack/invite-link]} context]
        [:section
         {:accessory [:button {:action_id "admin:invite-link-modal-open"}
                      (str (if invite-link "Update" "Add") " invite link")]}
         (str "*Invite Link:* "
              (str "let users from Sparkboard to join this Slack workspace."
                   (when-not invite-link "\n⚠️ Missing invite link")))])

      [:actions
       [:button {:action_id "admin:customize-messages-modal-open"}
        "Customize Messages"]
       [:button {:url (urls/install-slack-app (select-keys context [:sparkboard/jvm-root
                                                                    :slack/team-id]))} "Reinstall App"]
       [:button {:action_id 'checks-test:open} "Form Examples (dev)"]]
      [:section (str "_Updated "
                     (->> (java.util.Date.)
                          (.format (new java.text.SimpleDateFormat "h:mm:ss a, MMMM d"))) "_"
                     ". App " (:slack/app-id context) ", Team " (:slack/team-id context))])))

(defn main-menu [context]
  (list
    (if-let [board-id (:sparkboard/board-id context)]
      (let [{:keys [title domain]} (fire-jvm/read (str "settings/" board-id))]
        [:section
         {:accessory [:button {:url (urls/sparkboard-host domain)} "Visit Board"]}
         (str "This Slack team is connected to *" title "* on Sparkboard.")])
      [:section "No Sparkboard is linked to this Slack workspace."])

    (when-not (:sparkboard/account-id context)
      (link-account context))
    [:divider]
    (admin-menu context)))

(defn home [context]
  [:home (main-menu context)])

(defn invite-link-modal [{:keys [slack/invite-link]}]
  [:modal {:title "Set Invite Link"
           :callback_id "invite-link-modal"
           :submit "Save"}
   [:input {:label "Link"
            :optional true
            :element [:plain_text_input
                      {:initial_value (or invite-link "")
                       :placeholder "Paste the invite link from Slack here..."
                       :action_id "invite-link-input"}]}]
   [:context
    [:md
     (str "Learn how to create an invite link:\n"
          "https://slack.com/intl/en-de/help/articles/201330256-Invite-new-members-to-your-workspace#share-an-invite-link"
          "\nTake note of when your invite link expires, and how many members it will let you add.")]]])

(defn customize-messages-modal [context]
  [:modal {:title "Customize Messages"
           :callback_id "customize-messages-modal"
           :submit "Save"}
   (for [[k {:keys [label default]}] (seq team-messages)
         :let [db-value (get-in context [:slack/team :custom-messages k] "")]]
     [:input {:label label
              :optional true}
      [:plain_text_input {:initial_value db-value
                          :placeholder default
                          :multiline true
                          :action_id k}]])])

(defn shortcut-modal [context]
  [:modal {:title "Sparkboard"}
   (main-menu context)])

(defn destination-channel-groups [{:keys [slack/bot-token
                                          slack/bot-user-id]}]
  (->> (slack/web-api "conversations.list"
                      {:auth/token bot-token}
                      {:exclude_archived true
                       :types "public_channel,private_channel"})
       :channels
       (into [] (comp (filter :is_member)
                      (remove #(str/starts-with? (:name %) "project-"))
                      (map (fn [{:keys [name_normalized id]}]
                             {:value id :text [:plain_text name_normalized]}))))))

(defn team-broadcast-modal-compose
  ([context] (team-broadcast-modal-compose context {}))
  ([context local-state]
   [:modal {:title [:plain_text "Compose Broadcast"]
            :submit [:plain_text "Submit"]
            :callback_id "team-broadcast-modal-compose"
            :private_metadata local-state}
    ;; NB: private metadata is a String of max 3000 chars
    ;; See https://api.slack.com/reference/surfaces/views

    [:section "Sends a prompt to *all project teams*."]
    [:divider]
    [:input
     {:label [:plain_text "Message:"]}
     [:plain_text_input
      {:multiline true,
       :action_id "broadcast2:text-input"
       :initial_value "It's 2 o'clock! Please post a brief update of your team's progress so far today."}]]
    [:input
     {:label "Send responses to channel:"
      :optional true}
     [:static_select
      {:placeholder [:plain_text "Select a channel..."],
       :options (destination-channel-groups context)
       :action_id "broadcast2:channel-select"}]]
    [:input
     {:label "Options"
      :optional true}
     [:checkboxes
      {:action_id "broadcast-options"
       :options [{:value "collect-in-thread"
                  :text [:md "Collect responses in a thread"]}]}]]]))

(defn team-broadcast-message
  "Administrator broadcast to project channels, soliciting project update responses."
  [firebase-key {:keys [message response-channel]}]
  (list
    [:section [:md message]]
    (when response-channel
      (list
        [:actions
         {:block_id (transit/write {:broadcast/firebase-key firebase-key})}
         [:button {:style "primary"
                   :action_id "user:team-broadcast-response"
                   :value "click_me_123"}
          "Post an Update"]]
        [:context [:md (str "Replies will be sent to " (channel-link response-channel))]]))))

(defn team-broadcast-response
  "User response to broadcast - text field for project status update"
  [original-msg firebase-key]
  [:modal {:title [:plain_text "Project Update"]
           :callback_id "team-broadcast-response"
           :private_metadata {:broadcast/firebase-key firebase-key}
           :submit "Send"}
   [:input {:type "input"
            :label original-msg
            :block_id "sb-project-status1"}
    [:plain_text_input {:multiline true
                        :action_id "user:status-input"}]]])

(defn team-broadcast-response-msg [from-user-id from-channel-id msg]
  [[:divider]
   [:section [:md (str "_Project:_ * " (channel-link from-channel-id) "*, "
                       "_User:_ " (mention from-user-id))]]
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

(view/defmodal multi-select-modal
  {}
  [context payload state]
  [:modal {:title "Multi-Select examples"}
   [:section
    {:accessory
     [:multi_static_select
      (-> {:placeholder "Select some..."
           :action_id "multi-static-select"
           :on_action (fn [state value] (assoc state :multi-select value))
           :options [{:value "multi-1"
                      :text [:plain_text "Multi 1"]}
                     {:value "multi-2"
                      :text [:plain_text "Multi 2"]}
                     {:value "multi-3"
                      :text [:plain_text "Multi 3"]}]}
          (view/assoc-options :initial_options (:multi-select state)))]}
    "Multi-select"]
   [:section
    {:accessory
     [:multi_users_select
      (-> {:placeholder "Select some..."
           :action_id "multi-users-select"
           :on_action (fn [state value] (assoc state :multi-users-select value))}
          (view/assoc-some :initial_users (:multi-users-select state)))]}
    "Multi-users-select"]

   [:section
    {:accessory
     [:multi_conversations_select
      (-> {:placeholder "Select some..."
           :action_id "multi-conversations-select"
           :on_action (fn [state value] (assoc state :multi-conversations-select value))}
          (view/assoc-some :initial_conversations (:multi-conversations-select state)))]}
    "Multi-conversations-select"]

   [:section
    {:accessory
     [:multi_channels_select
      (-> {:placeholder "Select some..."
           :action_id "multi-channels-select"
           :on_action (fn [state value] (assoc state :multi-channels-select value))}
          (view/assoc-some :initial_channels (:multi-channels-select state)))]}
    "Multi-channels-select"]])

(view/defmodal checks-test
  {:counter 0
   :checks-test #{"value-test-1"}}
  [context payload state]
  [:modal {:title "State test"}
   [:actions
    [:button {:action_id "counter+"
              :on_action (fn [state _] (update state :counter inc))}
     (str "Clicked " (str "(" (:counter state) ")"))]
    [:datepicker (-> {:action_id "date-eg"
                      :on_action (fn [state value] (assoc state :date value))}
                     (view/assoc-some :initial_date (:date state)))]

    [:overflow {:action_id "overflow-eg"
                :on_action (fn [state value] (assoc state :overflow value))
                :options [{:value "o1"
                           :text [:plain_text "O1"]}
                          {:value "o2"
                           :text [:plain_text "O2"]}]}]
    [:users_select
     (-> {:placeholder "Pick a person"
          :action_id "users-select-eg"
          :on_action (fn [state user] (assoc state :user user))}
         (view/assoc-some :initial_user (:user state)))]

    [:radio_buttons
     (-> {:action_id "radio-eg"
          :on_action (fn [state value] (assoc state :radio value))
          :options [{:value "r1"
                     :text [:plain_text "Radio 1"]}
                    {:value "r2"
                     :text [:plain_text "Radio 2"]}]
          :initial_option {:value "r1" :text [:plain_text "Radio 1"]}}
         (view/assoc-option :initial_option (:radio state)))]

    [:checkboxes
     (-> {:action_id "checks-test"
          :on_action (fn [state value] (assoc state :checks value))
          :options [{:value "value-test-1"
                     :text [:md "Check 1"]}
                    {:value "value-test-2"
                     :text [:md "Check 2"]}]}
         (view/assoc-options :initial_options (:checks state)))]

    [:button {:action_id :multi-select-modal:push} "Multi-Select"]]


   (when-not (:hide-state? state)
     [:section (with-out-str (pp/pprint state))])
   [:actions
    [:button {:action_id "toggle-state-view"
              :on_action (fn [state value] (update state :hide-state? not))}
     (if (:hide-state? state) "show state" "hide state")]]])
