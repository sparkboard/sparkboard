(ns sparkboard.server.cloudflare-mailer
  (:require [clj-http.client :as http]
            [sparkboard.server.env :as env]
            [sparkboard.js-convert :refer [clj->json json->clj]]
            [sparkboard.validate :as vd]
            [malli.experimental.lite :as l]))


(let [{:cloudflare.mailer/keys [dkim-private-key
                                dkim-domain
                                dkim-selector
                                secret-key
                                url]} env/config]
  (defn send [{:as req
               :keys [from to
                      subject
                      text
                      html]}]
    (vd/assert req (l/schema
                    {:from {:email string?
                            :name (l/optional string?)}
                     :to {:email string?
                          :name (l/optional string?)}
                     :subject string?
                     :text (l/optional string?)
                     :html (l/optional string?)}))
    ;; send a json request to url
    (http/post url
               {:headers {"Content-Type" "application/json"
                          "X-Secret-Key" secret-key}
                :body (clj->json
                       {:personalizations [{:to [to]
                                            :dkim_domain dkim-domain
                                            :dkim_selector dkim-selector
                                            :dkim_private_key dkim-private-key}]
                        :from from
                        :subject subject
                        :content (cond-> []
                                         text
                                         (conj {:type "text/plain"
                                                :value text})
                                         html
                                         (conj {:type "text/html"
                                                :value html}))})})))

(comment
 (send {:from {:email "notifications@sparkboard.com"
               :name "Sparkboard Notifications"}
        :to {:email "mhuebert@gmail.com"}
        :subject "This is a test of our email service"
        :text "Hello, world"}))