(ns user
  (:require [clj-gatling.core :as clj-gatling]
            [clj-http.client :as client]))

(def open-home-tab
  {:token "RgpT68wASTX01dA5ZXv89CDJ", :team_id "T010MGVT4TV", :api_app_id "A013Z3YJ1GA", :event {:type "app_home_opened", :user "U012E480NTB", :channel "D01459SL9DJ", :tab "home", :view {:hash "1591374988.0eda19f1", :notify_on_close false, :callback_id "", :app_installed_team_id "T010MGVT4TV", :private_metadata "", :app_id "A013Z3YJ1GA", :root_view_id "V013ZALSCBZ", :submit nil, :type "home", :state {:values {}}, :close nil, :title {:type "plain_text", :text "View Title", :emoji true}, :previous_view_id nil, :external_id "", :id "V013ZALSCBZ", :blocks [{:type "section", :block_id "QVja", :text {:type "mrkdwn", :text "This Slack team is connected to *Slacked* on Sparkboard.", :verbatim false}, :accessory {:type "button", :text {:type "plain_text", :text "Visit Board", :emoji true}, :url "https://daveliepmann.ngrok.io/mock/slacked.sparkboard.com", :action_id "YwwI"}} {:type "divider", :block_id "v2kea"} {:type "section", :block_id "=5xqb", :text {:type "mrkdwn", :text ":hammer_and_wrench: ADMIN ACTIONS\n", :verbatim false}} {:type "divider", :block_id "sMI"} {:type "section", :block_id "PuVPg", :text {:type "mrkdwn", :text "*Team Broadcast:* send a message to all teams.", :verbatim false}, :accessory {:type "button", :action_id "admin:team-broadcast", :style "primary", :text {:type "plain_text", :text "Compose Broadcast", :emoji true}, :value "click_me_123"}} {:type "section", :block_id "2ovJd", :text {:type "mrkdwn", :text "*Invite Link:* let users from Sparkboard to join this Slack workspace.\n:warning: Missing invite link", :verbatim false}, :accessory {:type "button", :action_id "admin:invite-link-modal-open", :text {:type "plain_text", :text "Add invite link", :emoji true}}} {:type "actions", :block_id "veE", :elements [{:type "button", :action_id "admin:customize-messages-modal-open", :text {:type "plain_text", :text "Customize Messages", :emoji true}} {:type "button", :action_id "oxS", :text {:type "plain_text", :text "Reinstall App", :emoji true}, :url "https://daveliepmann.ngrok.io/slack/install?state=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJhbGciOiJSUzI1NiIsImlzcyI6InNwYXJrYm9hcmQtc3RhZ2luZy0yMDIwLTJAc3Bhcmtib2FyZC1zdGFnaW5nLmlhbS5nc2VydmljZWFjY291bnQuY29tIiwic3ViIjoic3Bhcmtib2FyZC1zdGFnaW5nLTIwMjAtMkBzcGFya2JvYXJkLXN0YWdpbmcuaWFtLmdzZXJ2aWNlYWNjb3VudC5jb20iLCJhdWQiOiJodHRwczpcL1wvaWRlbnRpdHl0b29sa2l0Lmdvb2dsZWFwaXMuY29tXC9nb29nbGUuaWRlbnRpdHkuaWRlbnRpdHl0b29sa2l0LnYxLklkZW50aXR5VG9vbGtpdCIsImlhdCI6MTU5MTM3NDk4OCwiZXhwIjoxNTkxMzc4NTg4LCJ1aWQiOiI6b3JnLnNwYXJrYm9hcmQuZmlyZWJhc2UudG9rZW5zXC9lbmNvZGUuY2xqIiwiY2xhaW1zIjp7InNsYWNrXC90ZWFtLWlkIjoiVDAxME1HVlQ0VFYifX0.ryi5y2xUdhmJnf1GpKNJlu52qmv7pya8sJ8YRtCsCi8kyA2_TCJOdWU7OzyQ-eANBeOLjo0XovmJtRYob73hqjAx5Av34zGHIyGISRVJFk1qIeoAm16hxFEws2A4eNrA6Vk0iAk8lUdSZ3J9E2Q9zqhWUb9EVFxqs70La0q8SlM_bKrLLY1EXQx0cLcLl1g8aJkzW0VjGPiRTdPrMzzt0XdOCNZ4afv05oTieEW134byVPOzK5uo1iaCYVTjLTFWxihU6p_Hyl5xAg-s_GA4Xq9KInkiJKRDoPrqNU0Ah4zDIic6VazWKQM8NjBRkCCZ9o870SP5E2s4TGhttcyy2g"}]} {:type "section", :block_id "lXF", :text {:type "mrkdwn", :text "_Updated 6:36:28 PM, June 5_. App A013Z3YJ1GA, Team T010MGVT4TV", :verbatim false}}], :team_id "T010MGVT4TV", :clear_on_close false, :bot_id "B013XNS830D"}, :event_ts "1591596152.965223"}, :type "event_callback", :event_id "Ev014YFUUKT7", :event_time 1591596152})

(def compose-broadcast-request
  {:payload "{\"type\":\"block_actions\",\"user\":{\"id\":\"U012E480NTB\",\"username\":\"dave.liepmann\",\"name\":\"dave.liepmann\",\"team_id\":\"T010MGVT4TV\"},\"api_app_id\":\"A013Z3YJ1GA\",\"token\":\"RgpT68wASTX01dA5ZXv89CDJ\",\"container\":{\"type\":\"view\",\"view_id\":\"V013ZALSCBZ\"},\"trigger_id\":\"1170127503555.1021573922947.56b9e4c3b0c17b539a125dda2203fa6f\",\"team\":{\"id\":\"T010MGVT4TV\",\"domain\":\"sparkboard-app\"},\"view\":{\"id\":\"V013ZALSCBZ\",\"team_id\":\"T010MGVT4TV\",\"type\":\"home\",\"blocks\":[{\"type\":\"section\",\"block_id\":\"QVja\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"This Slack team is connected to *Slacked* on Sparkboard.\",\"verbatim\":false},\"accessory\":{\"type\":\"button\",\"text\":{\"type\":\"plain_text\",\"text\":\"Visit Board\",\"emoji\":true},\"url\":\"https:\\/\\/daveliepmann.ngrok.io\\/mock\\/slacked.sparkboard.com\",\"action_id\":\"YwwI\"}},{\"type\":\"divider\",\"block_id\":\"v2kea\"},{\"type\":\"section\",\"block_id\":\"=5xqb\",\"text\":{\"type\":\"mrkdwn\",\"text\":\":hammer_and_wrench: ADMIN ACTIONS\\n\",\"verbatim\":false}},{\"type\":\"divider\",\"block_id\":\"sMI\"},{\"type\":\"section\",\"block_id\":\"PuVPg\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"*Team Broadcast:* send a message to all teams.\",\"verbatim\":false},\"accessory\":{\"type\":\"button\",\"action_id\":\"admin:team-broadcast\",\"style\":\"primary\",\"text\":{\"type\":\"plain_text\",\"text\":\"Compose Broadcast\",\"emoji\":true},\"value\":\"click_me_123\"}},{\"type\":\"section\",\"block_id\":\"2ovJd\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"*Invite Link:* let users from Sparkboard to join this Slack workspace.\\n:warning: Missing invite link\",\"verbatim\":false},\"accessory\":{\"type\":\"button\",\"action_id\":\"admin:invite-link-modal-open\",\"text\":{\"type\":\"plain_text\",\"text\":\"Add invite link\",\"emoji\":true}}},{\"type\":\"actions\",\"block_id\":\"veE\",\"elements\":[{\"type\":\"button\",\"action_id\":\"admin:customize-messages-modal-open\",\"text\":{\"type\":\"plain_text\",\"text\":\"Customize Messages\",\"emoji\":true}},{\"type\":\"button\",\"action_id\":\"oxS\",\"text\":{\"type\":\"plain_text\",\"text\":\"Reinstall App\",\"emoji\":true},\"url\":\"https:\\/\\/daveliepmann.ngrok.io\\/slack\\/install?state=eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9.eyJhbGciOiJSUzI1NiIsImlzcyI6InNwYXJrYm9hcmQtc3RhZ2luZy0yMDIwLTJAc3Bhcmtib2FyZC1zdGFnaW5nLmlhbS5nc2VydmljZWFjY291bnQuY29tIiwic3ViIjoic3Bhcmtib2FyZC1zdGFnaW5nLTIwMjAtMkBzcGFya2JvYXJkLXN0YWdpbmcuaWFtLmdzZXJ2aWNlYWNjb3VudC5jb20iLCJhdWQiOiJodHRwczpcL1wvaWRlbnRpdHl0b29sa2l0Lmdvb2dsZWFwaXMuY29tXC9nb29nbGUuaWRlbnRpdHkuaWRlbnRpdHl0b29sa2l0LnYxLklkZW50aXR5VG9vbGtpdCIsImlhdCI6MTU5MTM3NDk4OCwiZXhwIjoxNTkxMzc4NTg4LCJ1aWQiOiI6b3JnLnNwYXJrYm9hcmQuZmlyZWJhc2UudG9rZW5zXC9lbmNvZGUuY2xqIiwiY2xhaW1zIjp7InNsYWNrXC90ZWFtLWlkIjoiVDAxME1HVlQ0VFYifX0.ryi5y2xUdhmJnf1GpKNJlu52qmv7pya8sJ8YRtCsCi8kyA2_TCJOdWU7OzyQ-eANBeOLjo0XovmJtRYob73hqjAx5Av34zGHIyGISRVJFk1qIeoAm16hxFEws2A4eNrA6Vk0iAk8lUdSZ3J9E2Q9zqhWUb9EVFxqs70La0q8SlM_bKrLLY1EXQx0cLcLl1g8aJkzW0VjGPiRTdPrMzzt0XdOCNZ4afv05oTieEW134byVPOzK5uo1iaCYVTjLTFWxihU6p_Hyl5xAg-s_GA4Xq9KInkiJKRDoPrqNU0Ah4zDIic6VazWKQM8NjBRkCCZ9o870SP5E2s4TGhttcyy2g\"}]},{\"type\":\"section\",\"block_id\":\"lXF\",\"text\":{\"type\":\"mrkdwn\",\"text\":\"_Updated 6:36:28 PM, June 5_. App A013Z3YJ1GA, Team T010MGVT4TV\",\"verbatim\":false}}],\"private_metadata\":\"\",\"callback_id\":\"\",\"state\":{\"values\":{}},\"hash\":\"1591374988.0eda19f1\",\"title\":{\"type\":\"plain_text\",\"text\":\"View Title\",\"emoji\":true},\"clear_on_close\":false,\"notify_on_close\":false,\"close\":null,\"submit\":null,\"previous_view_id\":null,\"root_view_id\":\"V013ZALSCBZ\",\"app_id\":\"A013Z3YJ1GA\",\"external_id\":\"\",\"app_installed_team_id\":\"T010MGVT4TV\",\"bot_id\":\"B013XNS830D\"},\"actions\":[{\"action_id\":\"admin:team-broadcast\",\"block_id\":\"PuVPg\",\"text\":{\"type\":\"plain_text\",\"text\":\"Compose Broadcast\",\"emoji\":true},\"value\":\"click_me_123\",\"style\":\"primary\",\"type\":\"button\",\"action_ts\":\"1591596210.222743\"}]}"})

(defn home-tab-request [_]
  (= 200 (:status (client/post "http://localhost:3000/slack-api"
                               {:form-params open-home-tab
                                :content-type :json}))))

(defn compose-request-open-request [_]
  (= 200 (:status (client/post "http://localhost:3000/slack-api"
                               {:form-params compose-broadcast-request
                                :content-type :json}))))

(comment ; Load testing
  
  (client/post "http://localhost:3000/slack-api"
               {:form-params open-home-tab
                :content-type :json})

  (clj-gatling/run
    {:name "Simulation"
     :scenarios [{:name "Localhost test scenario"
                  :steps [{:name "Open Home Tab" :request home-tab-request}
                          {:name "Compose Request (open)" :request compose-request-open-request
                           ;;:sleep-before (constantly 1000)
                           }]}]}
    {:concurrency 150
     :requests 1000})

  ;; with logging `trace`
  ;; concurrency 1 -   1-2 seconds
  ;; concurrency 2 -   2-5 seconds
  ;; concurrency 5 -   5-6 seconds
  ;; concurrency 10 -  
  ;; concurrency 25 -  

  ;; with logging `warn`
  ;; concurrency 1 -  20-400ms
  ;; concurrency 2 -  10-500ms
  ;; concurrency 5 -  50-500ms
  ;; concurrency 10 - 50-500ms
  ;; concurrency 25 - 50-500ms
  
  (time (spit "debug-time"
              (zipmap (take 500 (range))
                      (take 500 (range)))))
  
  )

