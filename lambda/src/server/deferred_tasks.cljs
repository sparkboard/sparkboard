(ns server.deferred-tasks
  (:require ["aws-sdk" :as aws]
            [applied-science.js-interop :as j]
            [kitchen-async.promise :as p]
            [server.common :as common]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task registry

(defonce registry (atom {}))

(defn register!
  "Registers a task handler, to be called with [payload, event, context]
   where payload is a vector of [<task-keyword> & args]"
  [k f]
  (swap! registry assoc k f))

(defn alias!
  "Registers an existing function, to be called with args passed to the payload"
  [k f]
  (register! k (fn [[k :as message] _ _]
                 (apply f (rest message)))))

(register!
  ::default
  (fn [[k & data] _ _]
    (prn (str "No handler registered for " k ". Invoked with: " data))))

(defn invoke-task [[k :as message] event context]
  (let [message-handler (or (@registry k) (@registry ::default))]
    (message-handler message event context)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; lambdas - SNS topic & handler

(def topic-arn (common/env-var :DEFERRED_TASK_TOPIC_ARN))
(def SNS (delay (new aws/SNS #js{:apiVersion "2010-03-31"})))

(def aws? (or (common/env-var :LAMBDA_TASK_ROOT)
              (common/env-var :AWS_EXECUTION_ENV)))

(defn schedule!
  "Sends `payload` to `handle-deferred-task` in a newly invoked lambda"
  [payload]
  ;; https://docs.aws.amazon.com/sdk-for-javascript/v2/developer-guide/sns-examples-publishing-messages.html
  (if aws?
    (.publish ^js @SNS
              (j/obj :Message (common/write-transit payload)
                     :TopicArn topic-arn)
              (fn [err data]
                (when err
                  (js/console.error "error deferring task: " err))))
    ;; for local dev, invoke task with round-tripped data after a delay
    (p/do (p/timeout 200)
          (invoke-task (-> payload
                           common/write-transit
                           common/read-transit) nil nil)))
  nil)

(j/defn handler [^:js {:as event [Record] :Records} context]
  (p/resolve
    (invoke-task (-> (j/get-in Record [:Sns :Message])
                     common/read-transit)
                 event
                 context)))