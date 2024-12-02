(ns sb.server.email)

(defn send! [{:keys [to subject body]}]
  (println "Would send email to:" to)
  (println "Subject:" subject)
  (println body))


(comment
  (send! {:to "ema@mailbox.org"
         :subject "test"
         :body "Hello!"})
  )
