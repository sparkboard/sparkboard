(ns sb.server.email
  (:require [cognitect.aws.client.api :as aws]
            [cognitect.aws.credentials :as credentials]
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

(defn send! [{:as params :keys [to subject body]}]
  (if (= "dev" (:env env/config))
    (do
      (println "Would send email to:" to)
      (println "Subject:" subject)
      (println body))
    (aws-send! params)))


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
