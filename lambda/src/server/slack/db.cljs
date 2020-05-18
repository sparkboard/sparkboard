(ns server.slack.db)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Pseudo-database
(defonce db (atom {:slack/users nil
                   :sparkboard/users nil}))

(defn slack-user [slack-user-id]
  (let [users-by-id (:slack/users-by-id
                      (swap! db #(assoc % :slack/users-by-id
                                        (group-by (fn [usr] (get usr "id"))
                                                  (:slack/users-raw @db)))))]
    (first (get users-by-id slack-user-id))))

(comment
  ;; these requests don't block

  (p/let [rsp (slack/get+ "users.list")]
         ;; FIXME detect and log errors
         (swap! db #(assoc % :slack/users-raw (js->clj (j/get rsp :members)))))

  (p/let [rsp (slack/get+ "channels.list")]
         ;; FIXME detect and log errors
         (swap! db #(assoc % :slack/channels-raw (js->clj (j/get rsp :channels)
                                                          :keywordize-keys true)))))

(comment
  (reset! db {:slack/users nil
              :sparkboard/users nil})

  (:slack/users-raw @db)

  (map :name_normalized (:slack/channels-raw @db))
  (map :id (:slack/channels-raw @db))

  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Working with Slack data/requests

(comment
  (slack-user "U012E480NTB")

  )

(defn sparkboard-admin? [slack-username]                    ;; FIXME
  true)

(def project-channel-names                                  ;; FIXME
  (map :name_normalized (:slack/channels-raw @db)))
