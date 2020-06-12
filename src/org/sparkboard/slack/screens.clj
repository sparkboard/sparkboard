(ns org.sparkboard.slack.screens
  (:require [clojure.pprint :as pp]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.js-convert :refer [clj->json json->clj]]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.slack.api :as slack]
            [org.sparkboard.slack.view-examples :as examples]
            [org.sparkboard.slack.slack-db :as slack-db]
            [org.sparkboard.slack.urls :as urls]
            [org.sparkboard.slack.view :as v]
            [org.sparkboard.util.future :refer [try-future]]
            [taoensso.timbre :as log]))

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

(v/defview link-account [context]
  (when-let [linking-url (urls/link-sparkboard-account context)]
    (list
      [:section [:md "Please link your Sparkboard account:"]]
      [:actions
       [:button {:style "primary"
                 :url linking-url
                 :action-id :no-op/link-sparkboard}
        (str "Link Account")]])))

(declare home)
(v/defview invite-link-modal [context]
  [:modal {:title "Set Invite Link"
           :submit "Save"
           :on-submit (fn [{:as context :keys [input-values]}]
                        (fire-jvm/set-value (str "/slack-team/" (:slack/team-id context) "/invite-link/")
                                            (:invite-link input-values))
                        (v/home! home
                                 ;; update team-context to propagate invite-link change
                                 (merge context (slack-db/team-context (:slack/team-id context)))
                                 (:slack/user-id context)))}
   [:input {:label "Link"
            :optional true}
    [:plain-text-input
     {:set-value (or (:slack/invite-link context) "")
      :placeholder "Paste the invite link from Slack here..."
      :action-id :invite-link}]]
   [:context
    [:md
     (str "Learn how to create an invite link:\n"
          "https://slack.com/intl/en-de/help/articles/201330256-Invite-new-members-to-your-workspace#share-an-invite-link"
          "\nTake note of when your invite link expires, and how many members it will let you add.")]]])

(v/defview customize-messages-modal
  [context]
  [:modal {:title "Customize Messages"
           :submit "Save"
           :on-submit (fn [{:as context :keys [input-values]}]
                        (fire-jvm/update-value (str "/slack-team/"
                                                    (:slack/team-id context)
                                                    "/custom-messages") input-values))}
   (for [[k {:keys [label default]}] (seq team-messages)
         :let [db-value (get-in context [:slack/team :custom-messages k] "")]]
     [:input {:label label
              :optional true}
      [:plain-text-input {:set-value db-value
                          :placeholder default
                          :multiline true
                          :action-id k}]])])

(defn team-broadcast-response [board-id from-user-id from-channel-id msg]
  (let [domain (some-> board-id slack-db/board-domain)
        project-id (some-> (slack-db/linked-channel from-channel-id) :project-id)
        project-url (when (and domain project-id)
                      (str (urls/sparkboard-host domain) "/project/" project-id))]
    [[:divider]
     [:section
      {:accessory (when project-url [:button {:url project-url} "Project Page"])}
      [:md
       "from " (v/mention from-user-id) " in " (v/channel-link from-channel-id) ":\n"
       (v/blockquote msg)]]]))

(v/defview team-broadcast-response-compose
  "User response to broadcast - text field for project status update"
  [{:as context :keys [state]}]
  ;; response => destination-thread where team-responses are collected
  ;; reply    => team-thread where the message with "Reply" button shows up
  [:modal {:title "Project Update"
           :submit "Send"
           :on-submit
           (fn [{:as context :keys [input-values state]}]
             (log/trace :broadcast-response-values input-values)
             (let [{:keys [channel message]}
                   (slack/web-api "chat.postMessage"
                                  (let [{:keys [from-channel-id]} (fire-jvm/read (:broadcast/reply-path @state))
                                                 broadcast (fire-jvm/read (:broadcast/path @state))]
                                             {:blocks (team-broadcast-response
                                                        (:sparkboard/board-id context)
                                                        (:slack/user-id context)
                                                        from-channel-id
                                                        (:user-reply input-values))
                                              :channel (-> broadcast :response-channel)
                                     :thread_ts (-> broadcast :response-thread)}))
                   {:keys [permalink]} (slack/web-api "chat.getPermalink" {:channel channel
                                                                           :message_ts (:ts message)})]
               (slack/web-api "chat.postMessage"
                              {:channel (:broadcast/reply-channel @state)
                               :ts (:broadcast/reply-ts @state)
                               :blocks [[:section
                                         [:md "Thanks for your response! " (v/link "See what you wrote." permalink)]]]})))}
   [:input {:type "input"
            :label (:original-message @state)
            :block-id "sb-project-status1"}
    [:plain-text-input {:multiline true
                        :action-id :user-reply}]]
   [:context [:md "Your reply will be posted to "
              (v/channel-link
                (-> (:broadcast/path @state)
                    (fire-jvm/read)
                    :response-channel))]]])

(v/defview team-broadcast-content
  "Administrator broadcast to project channels, soliciting project update responses."
  ;; this is a *message* which is never updated
  [{:broadcast/keys [message response-channel id]}]
  (list
    [:section
     [:md
      #_(when sender-name (str "from *" sender-name "*:\n"))
      (v/blockquote message)]]
    (when response-channel
      (list [:actions
             {:block-id id}                                 ;; store hidden state in a message...
             [:button {:style "primary"
                       :action-id
                       {"user:team-broadcast-response"
                        (fn [{:as context :keys [slack/payload]}]
                          (let [broadcast-id (:block-id context) ;; ...read the hidden state
                                broadcast-ref (fire-jvm/->ref (str "/slack-broadcast/" broadcast-id))
                                original-message (fire-jvm/read (.child broadcast-ref "message"))
                                reply-ref (-> broadcast-ref (.child "replies") (.push))]
                            (fire-jvm/set-value reply-ref {:from-channel-id (-> payload :channel :id)})
                            (v/open! team-broadcast-response-compose
                                     (assoc context
                                       :initial-state {:original-message original-message
                                                       :broadcast/path (fire-jvm/ref-path broadcast-ref)
                                                       :broadcast/reply-path (fire-jvm/ref-path reply-ref)
                                                       :broadcast/reply-ts (-> payload :message :ts)
                                                       :broadcast/reply-channel (-> payload :channel :id)}))))}}
              "Reply"]]
            [:context
             [:md "Replies will be sent to " (v/channel-link response-channel)]]))))

(defn send-broadcast! [context {:as opts
                                :keys [message
                                       response-channel
                                       collect-in-thread]}]
  (let [broadcast-ref (.push (fire-jvm/->ref "/slack-broadcast"))
        sender-name (-> context :slack/payload :user :name)
        {thread :ts} (when response-channel
                       (slack/web-api "chat.postMessage"
                                      {:channel response-channel
                                       :blocks [[:section
                                                 [:md
                                                  "*" sender-name "*"
                                                  " sent a message to all teams:\n"
                                                  (v/blockquote message)]]]}))
        content (if response-channel
                  {:blocks (team-broadcast-content (merge context
                                                          #:broadcast{:id (.getKey broadcast-ref)
                                                                      :message message
                                                                      :response-channel response-channel}))}
                  {:text (v/blockquote (:message opts))})]
    (fire-jvm/set-value broadcast-ref
                        {:message message
                         :response-channel response-channel
                         :response-thread (when collect-in-thread thread)})
    (mapv #(slack/web-api "chat.postMessage"
                          {:auth/token (:slack/bot-token context)}
                          (merge
                            content
                            {:channel (:channel-id %)
                             :unfurl_media true
                             :unfurl_links true}))
          (slack-db/team->all-linked-channels (:slack/team-id context)))))

(v/defview team-broadcast-compose
  [context]
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
                                    :text [:plain-text
                                           ;; found error in production - max length of option text is 74 chars
                                           ;; TODO - truncate in hiccup layer?
                                           (v/truncate name_normalized 40)]})))))]
    [:modal {:title [:plain-text "Team Broadcast"]
             :submit [:plain-text "Send"]
             :on-submit (fn [{:as context {:keys [message response-channel options]} :input-values}]
                          (try-future
                            (send-broadcast! context
                                             {:message message
                                              :response-channel response-channel
                                              :collect-in-thread (contains? options "collect-in-thread")}))
                          [:update
                           [:modal {:title "Thanks!"}
                            [:section [:md "Broadcast received."]]]])}
     ;; NB: private metadata is a String of max 3000 chars
     ;; See https://api.slack.com/reference/surfaces/views
     [:input
      {:label [:plain-text "Message"]}
      [:plain-text-input
       {:multiline true,
        :action-id :message
        :placeholder (str "Write something to send to all " (count project-channels) " project teams.")}]]
     (if (seq destination-channel-options)
       (list
         [:input
          {:label "Collect responses:"
           :optional true}
          [:static-select
           {:placeholder [:plain-text "Destination channel"],
            :options destination-channel-options
            :action-id :response-channel}]]
         [:input
          {:label "Options"
           :optional true}
          [:checkboxes
           {:action-id :options
            :options [{:value "collect-in-thread"
                       :text [:md "Put responses in a thread"]}]}]])
       [:section [:md "💡 Add this app to a channel to enable collection of responses."]])]))

(v/defview admin-menu [context]
  (list
    [:section [:md "*Manage*"]]
    [:actions
     [:button {:style "primary"
               :action-id {:compose (fn [context]
                                      (case (-> context :slack/payload :view :type)
                                        "home" (v/open! team-broadcast-compose context)
                                        "modal" (v/replace! team-broadcast-compose context)))}} "Team Broadcast"]
     [:button {:action-id {:customize-messages (partial v/open! customize-messages-modal)}} "Customize Messages"]
     [:button {:action-id {:invite-link-modal (partial v/open! invite-link-modal)}}
      (str (if (:slack/invite-link context) "Change" "⚠️ Add") " invite link")]

     [:overflow {:action-id :no-op/re-link
                 :options [{:value "re-link"
                            :url (urls/link-sparkboard-account context)
                            :text [:plain-text "Re-Link Account"]}
                           {:value "re-install"
                            :url (str (:sparkboard/jvm-root context)
                                      "/slack/reinstall/"
                                      (:slack/team-id context))
                            :text [:plain-text "Re-install App"]}]}]]))

(defn main-menu [{:as context :sparkboard/keys [board-id account-id] :slack/keys [bot-token user-id]}]
  (list

    (when (and board-id (not account-id))
      (link-account context))

    (if-let [{:keys [title domain]} (some->> board-id (str "settings/") fire-jvm/read)]
      [:section
       {:accessory [:button {:url (urls/sparkboard-host domain)} "View Board"]}
       [:md "Connected to *" title "* on Sparkboard."]]
      [:section
       [:md "No Sparkboard is linked to this Slack workspace."]])

    (when (and board-id account-id (:is_admin (slack/user-info bot-token user-id)))
      (admin-menu context))
    [:section
     {:accessory (when-not (= "prod" (env/config :env))
                   (examples/dev-overflow context))
      :fields [[:md
                (str "_Updated: "
                     (->> (java.util.Date.)
                          (.format (new java.text.SimpleDateFormat "h:mm:ss a, MMMM d")))
                     "_")]
               [:md (str (:slack/app-id context) "." (:slack/team-id context))]]}]))

(v/defview home [context]
  [:home {} (main-menu context)])

(v/defview shortcut-modal [context]
  [:modal {:title "Sparkboard"}
   (main-menu context)])
