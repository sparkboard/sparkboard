(ns server.main
  "AWS Lambda <--> Slack API"
  ;; adapted from https://github.com/minimal-xyz/minimal-shadow-cljs-nodejs
  (:require ["aws-serverless-express" :as aws-express]
            ["aws-serverless-express/middleware" :as aws-middleware]
            ["body-parser" :as body-parser]
            ["express" :as express]
            [applied-science.js-interop :as j]
            [cljs.pprint :as pp]
            [kitchen-async.promise :as p]
            [lambdaisland.uri :as uri]
            [org.sparkboard.js-convert :refer [clj->json json->clj]]
            [server.common :refer [config decode-base64]]
            [server.deferred-tasks :as tasks]
            [server.slack :as slack]
            [server.slack.db :as mock-db]
            [org.sparkboard.slack.slack-db :as slack-db]
            [server.slack.handlers :as handlers]
            [server.common :as common]
            [server.perf :as perf]))
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; SparkBoard SlackBot server

(defn body->clj [body]
  (let [body (js->clj body :keywordize-keys true)]
    (cond-> body
      (:payload body)                                       ;; special case for Slack including a json-string as url-encoded body
      (update :payload json->clj))))

(j/defn handler*
  "Main AWS Lambda handler. Invoked by slackBot.
   See https://docs.aws.amazon.com/lambda/latest/dg/nodejs-handler.html"
  [^:js {:as req :keys [body headers]} ^js res]
  ;(tap> [:body body])
  ;(tap> [:headers headers])
  (perf/time-promise "handler*"
    (p/let [[kind data props] (cond (:payload body) [:interaction
                                                     (:payload body)
                                                     #:slack{:team-id (-> body :payload :team :id)
                                                             :user-id (-> body :payload :user :id)
                                                             :ts (-> body :payload :actions first :action_ts (some-> perf/slack-ts-ms))}]
                                    (:event body) [:event
                                                   (:event body)
                                                   #:slack{:team-id (:team_id body)
                                                           :user-id (-> body :event :user)
                                                           :app-id (:api_app_id body)
                                                           :ts (-> body :event :event_ts perf/slack-ts-ms)}]
                                    (:challenge body) [:challenge (:challenge body) nil])
            token (when (:slack/team-id props)
                    (perf/time-promise "slack-db/team->token"
                      (slack-db/team->token (-> config :slack :app-id) (:slack/team-id props))))
            props (merge props {:lambda/req req
                                :slack/token token})
            {:as result
             :keys [response
                    task]} (perf/time "build-response"
                                      (case kind
                                        ;; Slack API: identification challenge
                                        :challenge {:response data}

                                        ;; Slack Interaction (e.g. global shortcut)
                                        :interaction (handlers/handle-interaction! props data)

                                        ;; Slack Event
                                        :event (handlers/handle-event! props data)))]
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
    (.use (perf/req-measure :parse-body))
    (.use (body-parser/json))
    (.use (body-parser/urlencoded #js{:extended true}))
    (.use (fn [req res next]
            (j/update! req :body body->clj)
            (j/update! req :query js->clj :keywordize-keys true)
            (next)))
    (.use (perf/req-measure :parse-body))

    (.get "/slack/install" slack/oauth-install-redirect)
    (.get "/slack/oauth-redirect" slack/oauth-redirect)

    (cond-> (not common/aws?)
            (.get "/slack/install-local"
                  (fn [req res next]
                    (.redirect res (slack-db/get-install-link {:lambda/local? true})))))

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
