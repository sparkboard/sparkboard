(ns user
  (:require [clj-gatling.core :as clj-gatling]
            [clj-http.client :as client]
            [sample-slack-request-bodies]))

(comment ; Load testing
  
  (client/post "http://localhost:3000/slack-api"
               {:form-params sample-slack-request-bodies/open-home-tab
                :content-type :json})

  (defn home-tab-request [_]
    (= 200 (:status (client/post "http://localhost:3000/slack-api"
                                 {:form-params sample-slack-request-bodies/open-home-tab
                                  :content-type :json}))))

  (defn compose-request-open-request [_]
    (= 200 (:status (client/post "http://localhost:3000/slack-api"
                                 {:form-params sample-slack-request-bodies/compose-broadcast-request
                                  :content-type :json}))))

  (clj-gatling/run
    {:name "Simulation"
     :scenarios [{:name "Localhost test scenario"
                  :steps [{:name "Open Home Tab" :request home-tab-request}
                          {:name "Compose Request (open)" :request compose-request-open-request}]}]}
    {:concurrency 5})

  )
