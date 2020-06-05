(ns org.sparkboard.server.slack.screens
  (:require [clojure.string :as str]
            [org.sparkboard.slack.urls :as urls]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.server.slack.core :as slack]
            [org.sparkboard.server.slack.hiccup :as hiccup]
            [org.sparkboard.js-convert :refer [clj->json json->clj]]))

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

(def action-id-separator "::")

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
                                                                    :slack/team-id]))} "Reinstall App"]]
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

(defn team-broadcast-modal-choose
  ([context] (team-broadcast-modal-choose context nil))
  ([context private-data]
   [:modal {:title [:plain_text "Choose Broadcast Type"]
            :submit [:plain_text "Submit"]
            :callback_id "team-broadcast-modal-select"
            :private_metadata (or (some-> private-data (clj->json)) "")}
    ;; NB: private metadata is a String of max 3000 chars
    ;; See https://api.slack.com/reference/surfaces/views

    {:type "actions",
     :elements [[:button {:text {:type "plain_text",
                                 :text "Request project status",
                                 :emoji true},
                          :action_id "admin:team-broadcast-status"
                          :value "click_me_123"}]
                [:button {:text {:type "plain_text",
                                 :text "Offer help",
                                 :emoji true},
                          :action_id "admin:team-broadcast-help"
                          :value "click_me_789"}]]}]))

(defn team-broadcast-modal-compose
  ([context] (team-broadcast-modal-compose context nil))
  ([context private-data]
   [:modal {:title [:plain_text "Compose Broadcast"]
            :submit [:plain_text "Submit"]
            :callback_id "team-broadcast-modal-compose"
            :private_metadata (or (some-> private-data (clj->json)) "")}
    ;; NB: private metadata is a String of max 3000 chars
    ;; See https://api.slack.com/reference/surfaces/views

    [:section "Sends a prompt to *all project teams*."]
    [:divider]
    [:input
     {:label [:plain_text "Message:"]}
     [:plain_text_input
      {:multiline true,
       :action_id "broadcast2:text-input"
       :initial_value (case (:broadcast-type private-data)
                        :project-status "Hello. Please take a moment to let us know where you stand with your project. What have you achieved so far? What's next?"
                        :offer-help "Do you need the help of an expert? We have mentors available in AI, health, mobility, and more."
                        "")}]]
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
         [:button {:style "primary"
                   :action_id (str "user:team-broadcast-response"
                                   action-id-separator
                                   firebase-key)
                   :value "click_me_123"}
          "Post an Update"]]
        [:context [:md (str "Replies will be sent to " (channel-link response-channel))]]))))

(defn team-broadcast-response
  "User response to broadcast - text field for project status update"
  [original-msg firebase-key]
  [:modal {:title [:plain_text "Project Update"]
           :callback_id "team-broadcast-response"
           :private_metadata (clj->json {:broadcast/firebase-key firebase-key})
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
