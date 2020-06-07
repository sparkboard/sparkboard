(ns org.sparkboard.server.slack.screens
  (:require [clojure.pprint :as pp]
            [clojure.string :as str]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.js-convert :refer [clj->json json->clj]]
            [org.sparkboard.server.slack.core :as slack]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.slack.slack-db :as slack-db]
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
  (when-let [linking-url (urls/link-sparkboard-account context)]
    (list
      [:section (str "Please link your Sparkboard account:")]
      [:actions
       [:button {:style "primary"
                 :action_id "URL"
                 :url linking-url}
        (str "Link Account")]])))

(defn admin-menu [context]
  (when (:is_admin (slack/user-info (:slack/bot-token context) (:slack/user-id context)))
    (list
      [:section "📎 Admin"]
      [:actions
       [:button {:style "primary"
                 :action_id "admin:team-broadcast"}
        "Team Broadcast"]
       [:button {:action_id "admin:customize-messages-modal-open"} "Customize Messages"]

       (let [{:keys [slack/invite-link]} context]
         [:button {:action_id "admin:invite-link-modal-open"}
          (str (if invite-link "Change" "⚠️ Add") " invite link")])

       [:overflow {:action_id "admin-overflow"
                   :options [{:value "re-link"
                              :url (urls/link-sparkboard-account context)
                              :text [:plain_text "Re-Link Account"]}
                             {:value "re-install"
                              :url (str (:sparkboard/jvm-root context)
                                        "/slack/reinstall/"
                                        (:slack/team-id context))
                              :text [:plain_text "Re-install App"]}]}] ]

      [:section "🛠 Dev"]
      [:actions [:button {:action_id 'checks-test:open} "Form Examples"]]
      [:section (str "_Updated "
                     (->> (java.util.Date.)
                          (.format (new java.text.SimpleDateFormat "h:mm:ss a, MMMM d"))) "_"
                     ". App " (:slack/app-id context) ", Team " (:slack/team-id context))])))

(defn main-menu [{:as context :sparkboard/keys [board-id account-id]}]
  (list

    (when (and board-id (not account-id))
      (link-account context))

    (if-let [{:keys [title domain]} (some->> board-id (str "settings/") fire-jvm/read)]
      [:section
       {:accessory [:button {:url (urls/sparkboard-host domain)} "View Board"]}
       (str "Connected to *" title "* on Sparkboard.")]
      [:section "No Sparkboard is linked to this Slack workspace."])
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

(defn destination-channel-options [{:keys [slack/bot-token
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
   (let [project-teams (slack-db/team->all-linked-channels (:slack/team-id context))]
     [:modal {:title [:plain_text "Compose Broadcast"]
              :submit [:plain_text "Send"]
              :callback_id "team-broadcast-modal-compose"
              :private_metadata local-state}
      ;; NB: private metadata is a String of max 3000 chars
      ;; See https://api.slack.com/reference/surfaces/views
      [:input
       {:label [:plain_text "Message"]}
       [:plain_text_input
        {:multiline true,
         :action_id "broadcast2:text-input"
         :placeholder (str "Write something to send to all " (count project-teams) " project teams.")}]]
      (if-let [options (seq (destination-channel-options context))]
        (list
          [:input
           {:label "Collect responses:"
            :optional true}
           [:static_select
            {:placeholder [:plain_text "Destination channel"],
             :options options
             :action_id "broadcast2:channel-select"}]]
          [:input
           {:label "Options"
            :optional true}
           [:checkboxes
            {:action_id "broadcast-options"
             :options [{:value "collect-in-thread"
                        :text [:md "Put responses in a thread"]}]}]])
        [:section [:md "💡 Add this app to a channel to enable collection of responses."]])])))

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
  [original-msg private-metadata]
  [:modal {:title [:plain_text "Project Update"]
           :callback_id "team-broadcast-response"
           :private_metadata private-metadata
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
  [context state]
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
  [context state]
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
