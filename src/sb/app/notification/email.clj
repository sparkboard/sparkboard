(ns sb.app.notification.email
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [sb.app.notification.data :as data]
            [sb.app.time :as time]
            [sb.i18n :as i :refer [t]]
            [sb.routing :as routing]
            [sb.server.email :as email]
            [sb.server.env :as env]
            [taoensso.timbre :as log]
            [net.cgrand.xforms :as xf])
  (:import [java.time Duration Instant]))

(def GATHERING-DURATION-MINUTES 5)

(defn new-post [{{author :entity/created-by :post/keys [text parent]} :notification/subject}]
  (if (= :post (:entity/kind parent))
    (str (:account/display-name author) " " (t :tr/replied-to) "\n"
         ">> " (:prose/string (:post/text parent)) "\n"
         "> " (:prose/string text))
    (str (:account/display-name author) " " (t :tr/wrote) ":\n"
         "> " (:prose/string text))))

(defn new-member [{{:keys [membership/member]} :notification/subject}]
  (str (:account/display-name member) " " (t :tr/join) "."))

(defn new-invitation [{{:keys [membership/member]} :notification/subject}]
  (t :tr/you-are-invited-to-join))

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
  (t :tr/notifcation-email
     [(:account/display-name account)
      (->> (data/sort-and-group notifications)
           (map (fn [{:keys [first-notification-at notifications]}]
                  (str (time/small-datestamp first-notification-at) "\n\n"
                       (->> notifications
                            (map (fn [{:keys [context notifications]}]
                                   (str (when context
                                          (if (= :account (:entity/kind context))
                                            (str (:account/display-name context) " " (t :tr/messaged-you) ":\n"
                                                 (env/config :link-prefix)
                                                 (routing/path-for [`sb.app.chat.ui/chat {:other-id (:entity/id context)}]) "\n")
                                            (str "== " (str/trim (:entity/title context)) " ==\n"
                                                 (env/config :link-prefix) (routing/entity-path context 'ui/show) "\n")))
                                        (str/join "\n\n" (map show notifications)))))
                            (str/join "\n\n")))))
           (str/join "\n\n"))]))

(defn scheduled-at [account]
  (when (and (:account/email-verified? account)
             (not (= :account.email-frequency/never (:account/email-frequency account))))
    (when-let [notifications (seq (db/where [[:notification/email-to (:db/id account)]]))]
      (max-key #(.getEpochSecond %)
               (.plus (or (some-> (:account/last-emailed-at account) .toInstant )
                          Instant/EPOCH)
                      (case (:account/email-frequency account)
                        :account.email-frequency/daily (Duration/ofDays 1)
                        :account.email-frequency/hourly (Duration/ofHours 1)
                        :account.email-frequency/instant Duration/ZERO))
               (.plus (.toInstant (apply min-key #(.getTime %) (map :entity/created-at notifications)))
                      (Duration/ofMinutes GATHERING-DURATION-MINUTES))))))

(defn maybe-email! [account]
  (when-let [notifications (seq (db/where [[:notification/email-to (:db/id account)]]))]
    (binding [i/*selected-locale* (:account/locale account)]
      (email/send! {:to (:account/email account)
                    :subject (t :tr/activity-on-sparkboard)
                    :body (compose account notifications)}))
    (db/transact! (into [{:db/id (:db/id account)
                          :account/last-emailed-at (java.util.Date.)}]
                        (map (fn [notification]
                               [:db/retract (:db/id notification) :notification/email-to (:db/id account)]))
                        notifications))))

(defn schedules []
  (into []
        (comp (mapcat :notification/email-to)
              (distinct)
              (keep #(some-> (scheduled-at %) (vector %)))
              (xf/sort-by (comp #(.getEpochSecond %) first)))
        (db/where [:notification/email-to])))

(defn send-scheduled! []
  (doseq [[_ account] (take-while #(.isBefore (first %) (Instant/now) )
                                  (schedules))]
    (maybe-email! account)))

(def emailer (java.util.Timer. "emailer" true))

(defn start-polling! []
  (log/info "started polling every minute for notifications to be emailed")
  (.schedule emailer
             (proxy [java.util.TimerTask] [] (run [] (send-scheduled!)))
             0
             (* 60 1000))) ;; one Minute

(comment
  (start-polling!)

  (->> (schedules)
       (map #(update % 1 :account/email)))

  )
