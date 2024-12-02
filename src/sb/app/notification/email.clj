(ns sb.app.notification.email
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [sb.app.notification.data :as data]
            [sb.app.time :as time]
            [sb.routing :as routing]))

(def link-prefix "https://localhost:3000")

(defn new-post [{{author :entity/created-by :post/keys [text parent]} :notification/subject}]
  (if (= :post (:entity/kind parent))
    (str (:account/display-name author) " replied to:\n"
         ">> " (:prose/string (:post/text parent)) "\n"
         "> " (:prose/string text))
    (str (:account/display-name author) " wrote:\n"
         "> " (:prose/string text))))

(defn new-member [{{:keys [membership/member]} :notification/subject}]
  (str (:account/display-name member) " joined."))

(defn new-invitation [{{:keys [membership/member]} :notification/subject}]
  "You are invited to join.")

(defn new-message [message]
  (str "> " (:prose/string (:chat.message/content message))))

(defn show [notification]
  ((case (:entity/kind notification)
     :notification
     (case (:notification/type notification)
       :notification.type/new-post new-post
       :notification.type/new-member new-member
       :notification.type/new-invitation new-invitation
       (comp str deref))
     :chat.message new-message)
   notification))

(defn compose [account notifications]
  ;; TODO i18n
  (apply str "Dear " (:account/display-name account) ",\n"
         "here's what's been happening on sparkboard:\n\n"
         (->> (data/sort-and-group notifications)
              (map (fn [{:keys [first-notification-at notifications]}]
                     (str (time/small-datestamp first-notification-at) "\n\n"
                          (->> notifications
                               (map (fn [{:keys [context notifications]}]
                                      (str (when context
                                             (if (= :account (:entity/kind context))
                                               (str (:account/display-name context) " messaged you:\n"
                                                    link-prefix
                                                    (routing/path-for [`sb.app.chat.ui/chat {:other-id (:entity/id context)}]) "\n")
                                               (str "== " (str/trim (:entity/title context)) " ==\n"
                                                    link-prefix (routing/entity-path context 'ui/show) "\n")))
                                           (str/join "\n\n" (map show notifications)))))
                               (str/join "\n\n")))))
              (str/join "\n\n"))
         "\n\nGreetings\nThe Sparkbot"))
