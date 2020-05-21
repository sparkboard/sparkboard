(ns server.deferred-tasks
  (:require ["aws-sdk" :as aws]
            [applied-science.js-interop :as j]
            [kitchen-async.promise :as p]
            [server.common :as common])
  (:require-macros server.deferred-tasks))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task registry

(def registry (atom {}))

(defn register!
  "Registers a task handler, to be called with [payload, event, context]
   where payload is a vector of [<task-keyword> & args]"
  [k f]
  (swap! registry assoc k f))

(defn alias*
  "Registers an existing function, to be called with args passed to the payload"
  [k f]
  (register! k (fn [[k :as message] _ _]
                 (apply f (rest message)))))

(defn default [[k & args] _ _]
  (prn (str "No handler registered for " k ". Invoked with: " args)))

(register! `default default)

(defn invoke-task [[k :as message] event context]
  (let [message-handler (or (@registry k) (@registry `default))]
    (message-handler message event context)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; lambdas - SNS topic & handler

(def topic-arn (common/env-var :DEFERRED_TASK_TOPIC_ARN))
(def SNS (delay (new aws/SNS #js{:apiVersion "2010-03-31"})))

(def aws? (or (common/env-var :LAMBDA_TASK_ROOT)
              (common/env-var :AWS_EXECUTION_ENV)))

(defn publish!
  "Sends `payload` to `handle-deferred-task` in a newly invoked lambda"
  [payload]
  ;; https://docs.aws.amazon.com/sdk-for-javascript/v2/developer-guide/sns-examples-publishing-messages.html
  (p/try (if aws?
           (-> @SNS
               (j/call :publish
                       (j/obj :Message (common/write-transit payload)
                              :TopicArn topic-arn))
               (j/call :promise))
           ;; for local dev, invoke task with round-tripped data after a delay
           (p/do (p/timeout 200)
                 (invoke-task (-> payload
                                  common/write-transit
                                  common/read-transit) nil nil)))
         (p/catch js/Error e
           (js/console.error "error deferring task: " e)))
  nil)

(j/defn handler [^:js {:as event [Record] :Records} context]
  (p/resolve
    (invoke-task (-> (j/get-in Record [:Sns :Message])
                     common/read-transit)
                 event
                 context)))