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
            [server.common :as common]
            [server.common :refer [clj->json decode-base64 parse-json json->clj]]
            [server.deferred-tasks :as tasks]
            [server.slack :as slack]
            [server.slack.db :as mock-db]
            [server.slack-db-linking :as slack-db]
            [server.slack.handlers :as handlers]))

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
  [^:js {:as req :keys [body]} ^js res]
  (if (:challenge body)
    (.send res (:challenge body))
    (p/let [[kind data team-id] (cond (:payload body) [:interaction (:payload body) (-> body :payload :team :id)]
                                      (:event body) [:event (:event body) (:team_id body)]
                                      (:challenge body) [:challenge (:challenge body) nil])
            token (some-> team-id slack-db/team->token)
            {:as result
             :keys [response
                    task]} (case kind
                             ;; Slack API: identification challenge
                             :challenge {:response data}

                             ;; Slack Interaction (e.g. global shortcut)
                             :interaction (handlers/handle-interaction! token data)

                             ;; Slack Event
                             :event (handlers/handle-event! token data))]

      (assert (or (map? result) (nil? result)))
      (pp/pprint [(if result ::handled ::not-handled) body])
      (when task
        (pp/pprint task)
        (tasks/publish! task))

      (.send res response))))

(def app
  (doto ^js (express)
    ;; (.use (aws-middleware/eventContext))
    (.use (body-parser/json))
    (.use (body-parser/urlencoded #js{:extended true}))
    (.use (fn [req res next]
            (j/update! req :body body->clj)
            (j/update! req :query js->clj :keywordize-keys true)
            (next)))

    (.get "/slack/install" slack/install-redirect)
    (.get "/slack/oauth-redirect" slack/oauth-redirect)

    (.post "*" (fn [req res next] (#'handler* req res next)))))

(def server (aws-express/createServer app))

(def slack-handler
  (fn [event context]
    (aws-express/proxy server event context)))

(def deferred-task-handler tasks/handler)

(def dev-port 3000)
(defonce dev-server (atom nil))

(defn dev-stop []
  (some-> @dev-server (j/call :close))
  (reset! dev-server nil))

(defn ^:dev/after-load dev-start []
  (dev-stop)
  (reset! dev-server (j/call app :listen (doto 3000
                                           (->> (prn :started-server))))))

(comment
  (when goog/DEBUG
    (dev-start)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(comment

  (parse-json (:payload (uri/query-string->map (decode-base64 "cGF5bG9hZD0lN0IlMjJ0eXBlJTIyJTNBJTIyc2hvcnRjdXQlMjIlMkMlMjJ0b2tlbiUyMiUzQSUyMjJHSTVkSHNOYWJ4ZmZLc2N2eEszbkJ3aCUyMiUyQyUyMmFjdGlvbl90cyUyMiUzQSUyMjE1ODk1NTI5NDEuMjQ0ODQ4JTIyJTJDJTIydGVhbSUyMiUzQSU3QiUyMmlkJTIyJTNBJTIyVDAxME1HVlQ0VFYlMjIlMkMlMjJkb21haW4lMjIlM0ElMjJzcGFya2JvYXJkLWFwcCUyMiU3RCUyQyUyMnVzZXIlMjIlM0ElN0IlMjJpZCUyMiUzQSUyMlUwMTJFNDgwTlRCJTIyJTJDJTIydXNlcm5hbWUlMjIlM0ElMjJkYXZlLmxpZXBtYW5uJTIyJTJDJTIydGVhbV9pZCUyMiUzQSUyMlQwMTBNR1ZUNFRWJTIyJTdEJTJDJTIyY2FsbGJhY2tfaWQlMjIlM0ElMjJzcGFya2JvYXJkJTIyJTJDJTIydHJpZ2dlcl9pZCUyMiUzQSUyMjExMzk0Mzg2ODYzMDUuMTAyMTU3MzkyMjk0Ny5kNDhhNDExNDBmMjJhMjc4YTlmNGUxZTVkNDliMDBjYSUyMiU3RA=="))))
  )
