(ns server.deferred-tasks
  (:require ["aws-sdk" :as aws]
            [applied-science.js-interop :as j]
            [kitchen-async.promise :as p]
            [org.sparkboard.transit :as transit]
            [org.sparkboard.js-convert :refer [clj->json]]
            [org.sparkboard.common :as common]
            [server.perf :as perf])
  (:require-macros server.deferred-tasks))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Task registry

(defonce registry (atom {}))

(defn register-raw!
  "Registers a task handler, to be called with [payload, event, context]
   where payload is a vector of [<task-keyword> & args]"
  [k f]
  (swap! registry assoc k f))

(defn register-handler*
  "Registers a task handler to be called with the args passed to the payload"
  [k f]
  (register-raw! k (fn [[k :as message] _]
                     (apply f (rest message)))))

(defn default [[k & args] _]
  (prn (str "No handler registered for " k ". Invoked with: " args)))

(register-raw! `default default)

(defn handle-task [{[k :as message] :task :as payload} context]
  (let [handler (or (@registry k) (@registry `default))]
    (handler message context)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; lambdas

(def lambda (delay (new aws/Lambda)))

(defn invoke-lambda*
  [payload]
  (p/try
    (p/promise [resolve reject]
      (j/call @lambda :invoke
              (j/obj :FunctionName (common/env-var :TASK_HANDLER)
                     :InvocationType "Event"
                     :Payload (-> (transit/write payload)
                                  (js/JSON.stringify)))
              (fn [err res]
                (if err (reject err) (resolve res)))))
    (p/catch js/Error e
      (js/console.error "Error invoking task lambda: " e))))

(defn invoke-lambda
  "Invokes task-handler lambda with `payload`, invoking locally during development"
  [payload]
  ;; https://docs.aws.amazon.com/sdk-for-javascript/v2/developer-guide/sns-examples-publishing-messages.html
  (if common/aws?
    (invoke-lambda* payload)
    ;; for local dev, invoke task locally, after a delay, with payload round-trip through transit-json
    (p/do (p/timeout 200)
          (handle-task (-> payload transit/write transit/read) nil))))

(j/defn lambda-handler
  "Lambda handler function, receives payload from `invoke-lambda`"
  [transit-payload context]
  (let [payload (transit/read transit-payload)]
    (tap> [:INVOKE_LAMBDA_DELAY (perf/seconds-since (:task-invoke/ts payload))])
    (tap> [:task-payload payload])
    (perf/time-promise "invoke-task-handler"
      (handle-task payload context))))
