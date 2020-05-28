(ns server.main
  "AWS Lambda <--> Slack API"
  ;; adapted from https://github.com/minimal-xyz/minimal-shadow-cljs-nodejs
  (:require ["aws-serverless-express" :as aws-express]
            ["aws-serverless-express/middleware" :as aws-middleware]
            ["body-parser" :as body-parser]
            ["express" :as express]
            [applied-science.js-interop :as j]
            [kitchen-async.promise :as p]
            [lambdaisland.uri :as uri]
            [org.sparkboard.js-convert :refer [clj->json json->clj ->clj]]
            [org.sparkboard.common :refer [config decode-base64]]
            [server.deferred-tasks :as tasks]
            [server.slack :as slack]
            [org.sparkboard.slack.slack-db :as slack-db]
            [server.slack.handlers :as handlers]
            [org.sparkboard.common :as common]
            [server.perf :as perf]
            [org.sparkboard.slack.links :as links]
            [org.sparkboard.transit :as transit]))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; SparkBoard SlackBot server

(defn body->clj [body content-type]
  (when body
    (let [body (case content-type "application/json" (json->clj body)
                                  "application/transit+json" (transit/read body)
                                  body)]
      (cond-> body
        (:payload body)                                     ;; special case for Slack including a json-string as url-encoded body
        (update :payload json->clj)))))

(def app-id (-> config :slack :app-id))

(j/defn handler*
  "Main AWS Lambda handler. Invoked by slackBot.
   See https://docs.aws.amazon.com/lambda/latest/dg/nodejs-handler.html"
  [^:js {:as req :keys [body headers query]} ^js res]
  ;(tap> [:body body])
  ;(tap> [:headers headers])

  (prn :THE_BODY body)
  (perf/time-promise "handler*"
    (p/let [[kind data team-id user-id ts] (cond (:payload body) [:interaction
                                                                  (:payload body)
                                                                  (-> body :payload :team :id)
                                                                  (-> body :payload :user :id)
                                                                  (-> body :payload :actions first :action_ts (some-> perf/slack-ts-ms))]
                                                 (:event body) [:event
                                                                (:event body)
                                                                (:team_id body)
                                                                (-> body :event :user)
                                                                (-> body :event :event_ts perf/slack-ts-ms)]
                                                 (:sparkboard body) (let [{:as data
                                                                           :slack/keys [team-id
                                                                                        user-id]} (:sparkboard body)]
                                                                      [:sparkboard
                                                                       data
                                                                       team-id user-id
                                                                       (.now js/Date)])
                                                 (:challenge body) [:challenge (:challenge body) nil])
            props (p/let [team (some-> team-id (slack-db/linked-team))
                          token (get-in team [:app (keyword app-id) :bot-token])
                          user (some-> user-id slack-db/linked-user)]
                    {:lambda/root (common/lambda-root-url req)
                     :slack/team-id team-id
                     :slack/user-id user-id
                     :slack/app-id app-id
                     :slack/token token
                     :slack/user user
                     :slack/ts ts
                     :sparkboard/account-id (:account-id user)
                     :sparkboard/board-id (:board-id team)
                     :env (:env common/config "dev")})
            {:as result
             :keys [response
                    task]} (perf/time "build-response"
                                      (case kind
                                        ;; Slack API: identification challenge
                                        :challenge {:response data}

                                        ;; Slack Interaction (e.g. global shortcut)
                                        :interaction (handlers/handle-interaction! props data)

                                        ;; Slack Event
                                        :event (handlers/handle-event! props data)

                                        :sparkboard (handlers/handle-sparkboard-action! props data)))]
      (assert (or (map? result) (nil? result)))
      ;(pp/pprint [(if result ::handled ::not-handled) body])
      (when task
        ;(perf/time "pprint-task" (pp/pprint (str "[handler*] task: " task)))
        (perf/time-promise "tasks/publish!"
          (tasks/invoke-lambda {:task task
                                :slack/ts (:slack/ts props)
                                :task-invoke/ts (js/Date.now)})))
      (.send res response)
      nil)))

(def app
  (doto ^js (express)

    ;; POST body
    (.use (fn [req res next]
            (j/update! req :body body->clj (j/get-in req [:headers :content-type]))
            (next)))

    ;; query params
    (.use (body-parser/urlencoded #js{:extended true}))
    (.use (fn [req res next]
            (j/update! req :query ->clj)
            (next)))


    (.get "/slack/install" slack/oauth-install-redirect)
    (.get "/slack/oauth-redirect" slack/oauth-redirect)

    (cond-> (not common/aws?)
            (.get "/slack/install-local"
                  (fn [req res next]
                    (.redirect res (links/install-slack-app {:lambda/local? true})))))

    (.post "*" (fn [req res next] (#'handler* req res next)))))

(def server (aws-express/createServer app))

(def slack-handler
  (fn [event context]
    (aws-express/proxy server event context)))

(def task-handler tasks/lambda-handler)

(defn init []
  (common/init-config)
  (when common/aws?
    (add-tap prn)))

(defonce _ (init))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(comment

  (json->clj (:payload (uri/query-string->map (decode-base64 "cGF5bG9hZD0lN0IlMjJ0eXBlJTIyJTNBJTIyc2hvcnRjdXQlMjIlMkMlMjJ0b2tlbiUyMiUzQSUyMjJHSTVkSHNOYWJ4ZmZLc2N2eEszbkJ3aCUyMiUyQyUyMmFjdGlvbl90cyUyMiUzQSUyMjE1ODk1NTI5NDEuMjQ0ODQ4JTIyJTJDJTIydGVhbSUyMiUzQSU3QiUyMmlkJTIyJTNBJTIyVDAxME1HVlQ0VFYlMjIlMkMlMjJkb21haW4lMjIlM0ElMjJzcGFya2JvYXJkLWFwcCUyMiU3RCUyQyUyMnVzZXIlMjIlM0ElN0IlMjJpZCUyMiUzQSUyMlUwMTJFNDgwTlRCJTIyJTJDJTIydXNlcm5hbWUlMjIlM0ElMjJkYXZlLmxpZXBtYW5uJTIyJTJDJTIydGVhbV9pZCUyMiUzQSUyMlQwMTBNR1ZUNFRWJTIyJTdEJTJDJTIyY2FsbGJhY2tfaWQlMjIlM0ElMjJzcGFya2JvYXJkJTIyJTJDJTIydHJpZ2dlcl9pZCUyMiUzQSUyMjExMzk0Mzg2ODYzMDUuMTAyMTU3MzkyMjk0Ny5kNDhhNDExNDBmMjJhMjc4YTlmNGUxZTVkNDliMDBjYSUyMiU3RA=="))))
  )
