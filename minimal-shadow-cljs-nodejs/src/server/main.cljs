(ns server.main
  "AWS Lambda <--> Slack API

  Original template from https://github.com/minimal-xyz/minimal-shadow-cljs-nodejs"
  (:require ["aws-serverless-express" :as aws-express]
            ["aws-serverless-express/middleware" :as aws-middleware]
            ["body-parser" :as body-parser]
            ["express" :as express]
            [applied-science.js-interop :as j]
            [cljs.pprint :as pp]
            [kitchen-async.promise :as p]
            [lambdaisland.uri :as uri]
            [server.common :refer [clj->json decode-base64 parse-json json->clj]]
            [server.slack.db :as slack-db]
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
  (let [request-type (cond (:challenge body) :challenge
                           (:event body) :event
                           (:payload body) :interaction)]
    (pp/pprint ["[handler] request-type:" request-type])
    (pp/pprint ["[handler] body:" body])
    ;(pp/pprint ["[handler] event:" event])

    (p/try (p/let [response (case request-type
                              ;; Slack API: identification challenge
                              :challenge
                              (:challenge body)

                              ;; Slack Interaction (e.g. global shortcut)
                              :interaction
                              (handlers/handle-interaction! (:payload body))

                              ;; Slack Event
                              :event
                              (handlers/handle-event! (:event body))

                              ;; "broadcast update request to project channels"
                              (handlers/request-updates! (-> (get-in body [:event :user])
                                                             slack-db/slack-user
                                                             (get "name"))
                                                         (map :id (:slack/channels-raw @slack-db/db))))]

             (.send res (when (string? response) response)))
           (p/catch js/Error e
             (pp/pprint [:error e])
             (-> res (.status 500) (.send "Server error"))))))

(def app
  (doto ^js (express)
    ;; (.use (aws-middleware/eventContext))
    (.use (body-parser/json))
    (.use (body-parser/urlencoded #js{:extended true}))
    (.use (fn [req res next]
            (j/update! req :body body->clj)
            (next)))
    (.use "*" (fn [req res next] (#'handler* req res next)))))

(def server (aws-express/createServer app))

(def handler (fn [event context] (aws-express/proxy server event context)))

(def dev-port 3000)
(defonce dev-server (atom nil))

(defn dev-stop []
  (some-> @dev-server (j/call :close))
  (reset! dev-server nil))

(defn dev-start []
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
