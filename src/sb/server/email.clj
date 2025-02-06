(ns sb.server.email
  (:require [clj-http.client :as http]
            [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
            [jsonista.core :as j]
            [sb.i18n :as i :refer [t]]
            [sb.server.env :as env]))

(def ses (aws/client {:api :email
                      :region (env/config :aws.ses/region)
                      :credentials-provider
                      (credentials/basic-credentials-provider
                       {:access-key-id (env/config :aws.ses/access-key-id)
                        :secret-access-key (env/config :aws.ses/secret-access-key)})}))

(aws/validate-requests ses true)

(defn aws-send! [{:keys [to subject body]}]
  (aws/invoke ses {:op :SendEmail
                   :request {:Source (env/config :sparkbot/email)
                             :Destination {:ToAddresses [to]}
                             :Message {:Subject {:Data subject}
                                       :Body {:Text {:Data body}}}}}))

(defn sendgrid-send! [{:keys [to subject body]}]
  (http/post "https://api.sendgrid.com/v3/mail/send"
             {:headers {"Content-Type" "application/json"
                        "Authorization" (str "Bearer " (env/config :sendgrid/api-key))}
              :body (-> {:personalizations [{:to [{:email to}]}]
                         :from {:email (env/config :sparkbot/email)}
                         :subject subject
                         :content [{:type "text/plain"
                                    :value body}]}
                        j/write-value-as-string)}))

(defn send! [{:as params :keys [to subject body]}]
  (if (= "dev" (:env env/config))
    (do
      (println "Would send email to:" to)
      (println "Subject:" subject)
      (println body))
    (sendgrid-send! params)))


(defn send-to-account!
  "Sends an email with greetings added to the email address of `account` if that email address is verified.
  Use this function instead of send `send!` if possible."
  [{:keys [account subject body]}]
  (if (:account/email-verified? account)
    (send! {:to (:account/email account)
            :subject subject
            :body (binding [i/*selected-locale* (:account/locale account)]
                    (t :tr/email-template [(:account/display-name account)
                                           body]))})))


(comment
  (send! {:to "ema@mailbox.org"
         :subject "test"
         :body "Hello!"})
  )
