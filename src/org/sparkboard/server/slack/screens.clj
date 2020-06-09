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
            [taoensso.timbre :as log]
            [org.sparkboard.server.env :as env]))

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

(declare checks-test)

(defn admin-menu [context]
  (list
    [:section "*Manage*"]
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
                            :text [:plain_text "Re-install App"]}]}]]

    [:divider]
    (when-not (= "prod" (env/config :env))
      [:actions
       [:button {:action-id (-> checks-test :actions :open)}
        "Dev: Form Examples"]])))

(defn main-menu [{:as context :sparkboard/keys [board-id account-id] :slack/keys [bot-token user-id]}]
  (list

    (when (and board-id (not account-id))
      (link-account context))

    (if-let [{:keys [title domain]} (some->> board-id (str "settings/") fire-jvm/read)]
      [:section
       {:accessory [:button {:url (urls/sparkboard-host domain)} "View Board"]}
       (str "Connected to *" title "* on Sparkboard.")]
      [:section "No Sparkboard is linked to this Slack workspace."])

    (when (and board-id account-id (:is_admin (slack/user-info bot-token user-id)))
      (admin-menu context))

    [:section
     {:fields [[:md
                (str "_Updated: "
                     (->> (java.util.Date.)
                          (.format (new java.text.SimpleDateFormat "h:mm:ss a, MMMM d")))
                     "_")]
               [:md (str (:slack/app-id context) "." (:slack/team-id context))]]}]))

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
   (let [project-channels (into #{}
                                (map :channel-id)
                                (slack-db/team->all-linked-channels (:slack/team-id context)))
         destination-channel-options
         (->> (slack/web-api "conversations.list"
                             {:exclude_archived true
                              :types "public_channel,private_channel"})
              :channels
              (into [] (comp (filter :is_member)
                             (remove (comp project-channels :id))
                             (map (fn [{:keys [name_normalized id]}]
                                    {:value id
                                     :text [:plain_text
                                            ;; found error in production - max length of option text is 74 chars
                                            ;; TODO - truncate in hiccup layer?
                                            (view/truncate name_normalized 40)]})))))]
     [:modal {:title [:plain_text "Team Broadcast"]
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
         :placeholder (str "Write something to send to all " (count project-channels) " project teams.")}]]
      (if (seq destination-channel-options)
        (list
          [:input
           {:label "Collect responses:"
            :optional true}
           [:static_select
            {:placeholder [:plain_text "Destination channel"],
             :options destination-channel-options
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
  [firebase-key {:keys [message response-channel]} sender-name]
  (list
    [:section [:md
               (when sender-name (str "from *" sender-name "*:\n"))
               (view/blockquote message)]]
    (when response-channel
      (list [:actions
             {:block_id (transit/write {:broadcast/firebase-key firebase-key})}
             [:button {:style "primary"
                       :action_id "user:team-broadcast-response"}
              "Reply"]]
            [:context
             [:md "Replies will be sent to " (view/channel-link response-channel)]]))))

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

(defn team-broadcast-response-msg [board-id from-user-id from-channel-id msg]
  (let [domain (some-> board-id slack-db/board-domain)
        project-id (some-> (slack-db/linked-channel from-channel-id) :project-id)
        project-url (when (and domain project-id)
                      (str (urls/sparkboard-host domain) "/project/" project-id))]
    [[:divider]
     [:section
      (when project-url {:accessory [:button {:url project-url} "Project Page"]})
      [:md "from " (view/mention from-user-id) " in " (view/channel-link from-channel-id) ":\n"]]
     [:section [:md (view/blockquote msg)]]]))

(comment
  (hiccup/->blocks team-broadcast-modal-compose)
  (hiccup/->blocks [:md "hi"]))

(view/defmodal multi-select-modal
  [{::view/keys [state]}]
  [:modal {:title "Multi-Select examples"
           :submit "Submit"
           ::view/on-submit (fn [context] (log/info :ON_SUBMIT context))}
   [:section
    {:accessory
     [:multi_static_select
      {:placeholder "Select some..."
       ::view/action (fn static [context state value]
                       (swap! state assoc :static value))
       ::view/value (:static @state)
       :options [{:value "multi-1"
                  :text [:plain_text "Multi 1"]}
                 {:value "multi-2"
                  :text [:plain_text "Multi 2"]}
                 {:value "multi-3"
                  :text [:plain_text "Multi 3"]}]}]}
    "Multi-select"]
   [:section
    {:accessory
     [:multi_users_select
      {:placeholder "Select some..."
       ::view/action (fn users [context state value]
                       (swap! state assoc :users value))
       ::view/value (:users @state)}]}
    "Multi-users-select"]

   [:section
    {:accessory
     [:multi_conversations_select
      {:placeholder "Select some..."
       ::view/action (fn conversations [context state value]
                       (swap! state assoc :conversations value))
       ::view/value (:conversations @state)}]}
    "Multi-conversations-select"]

   [:input
    {:label "Multi-channels-select (as input)"}
    [:multi_channels_select
     {:placeholder "Select some..."
      :action-id :multi-channel}]]])

(view/defmodal checks-test
  {:initial-state {:counter 0
                   :show-state? true
                   :checks-test #{"value-test-1"}}
   :actions {:open
             (fn [context state _] (view/open! (::view/view context) context))
             :datepicker
             (fn [context state value] (swap! state assoc :datepicker value))
             :overflow
             (fn [context state value] (swap! state assoc :overflow value))
             :users_select
             (fn [context state value] (swap! state assoc :users_select value))
             :radio_buttons
             (fn [context state value] (swap! state assoc :radio_buttons value))
             :checkboxes
             (fn [context state value] (swap! state assoc :checkboxes value))
             :toggle-state-view
             (fn [context state value] (swap! state update :show-state? not))
             :multi-select-open
             (fn [context state value]
               (view/push! multi-select-modal context)
               nil)}}
  [{::view/keys [actions state]}]
  [:modal {:title "State test"}
   [:actions
    [:button {::view/action
              (fn counter [context state _]
                (log/info :COUNTER_STATE state)
                (swap! state update :counter inc))}
     (str "Clicked " (str "(" (:counter @state) ")"))]
    [:datepicker {:action-id (:datepicker actions)
                  ::view/value (:datepicker @state)}]

    [:overflow {:action-id (:overflow actions)
                :options [{:value "o1"
                           :text [:plain_text "O1"]}
                          {:value "o2"
                           :text [:plain_text "O2"]}]}]

    [:users_select
     {:placeholder "Pick a person"
      :action-id (:users_select actions)
      ::view/value (:users_select @state)}]

    [:button {:action-id (:multi-select-open actions)} "Open multi-select modal"]]
   [:actions

    [:radio_buttons
     {:action-id (:radio_buttons actions)
      ::view/value (:radio_buttons @state)
      :options [{:value "r1"
                 :text [:plain_text "Radio 1"]}
                {:value "r2"
                 :text [:plain_text "Radio 2"]}]
      :initial_option {:value "r1" :text [:plain_text "Radio 1"]}}]

    [:checkboxes
     {:action-id (:checkboxes actions)
      ::view/value (:checkboxes @state)
      :options [{:value "value-test-1"
                 :text [:md "Check 1"]}
                {:value "value-test-2"
                 :text [:md "Check 2"]}]}]]

   (when (:show-state? @state)
     [:section (with-out-str (pp/pprint @state))])
   [:actions
    [:button {:action-id (:toggle-state-view actions)}
     (if (:show-state? @state) "hide state" "show state")]]])
