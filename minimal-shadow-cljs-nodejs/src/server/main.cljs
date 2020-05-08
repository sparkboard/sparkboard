(ns server.main
  "AWS Lambda <--> Slack API

  Original template from https://github.com/minimal-xyz/minimal-shadow-cljs-nodejs"
  (:require [applied-science.js-interop :as j]
            [clojure.edn :as edn]
            [clojure.string :as string]))

(println "Hello, this is ClojureScript; how may I direct your call?")


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Environment variables/config
(def config (edn/read-string (j/get js/process.env :SPARKBOARD_CONFIG)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; HTTP requests
(def node-fetch (js/require "node-fetch"))

(defn- fetch+ [decode-fn url opts]
  (-> (node-fetch url opts)
      (.then (fn [^js/Response res] (if (.-ok res)
                                     (decode-fn res)
                                     (throw (ex-info "Invalid network request"
                                                     {:status (.-status res)})))))))

(defn decode-json [^js/Response resp] (.json resp))

(def ^js/Promise fetch-json+ (partial fetch+ decode-json))

(defn slack-api-get [family-method callback-fn]
  (-> (fetch+ decode-json
              (str "https://slack.com/api/" family-method)
              #js {"method" "GET"
                   "Content-Type" "application/json; charset=utf-8"
                   :headers #js {"Authorization" (str "Bearer " (-> config :slack :bot-user-oauth-token))}})
      (.then callback-fn)))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defonce db (atom {:slack/users nil
                   :sparkboard/users nil}))

(slack-api-get "users.list"
               ;; FIXME detect and log errors
               (fn [rsp] (swap! db #(assoc % :slack/users (j/get rsp :members)))))

(comment
  (:slack/users @db)

  (reset! db {:slack/users nil
              :sparkboard/users nil})
  
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; FIXME
(defn from-slack? [event] ;; FIXME ran into trouble with
                        ;; goog.crypt.Sha256 so using a hack for
                        ;; now. TODO implement HMAC check per
                        ;; https://api.slack.com/authentication/verifying-requests-from-slack
  (= (j/get-in event [:headers :user-agent])
     "Slackbot 1.0 (+https://api.slack.com/robots)")
  ;; TODO
  ;; 1. Check X-Slack-Signature HTTP header
  #_  (let [s (string/join ":" [(j/get evt :version)
                            (j/get-in evt [:headers :x-slack-request-timestamp])
                            (j/get evt :body)])]))

(defn slack-username [slack-user-id]
  )

(defn sparkboard-admin? [slack-username]
  )


(defn handler [event _context callback]
  ;; https://docs.aws.amazon.com/lambda/latest/dg/nodejs-handler.html
  (println "event:" event)
  (println "event->headers" (j/get event :headers))
  (println "event->headers->user-agent:" (j/get-in event [:headers :user-agent]))
  (case (j/get-in event [:requestContext :http :path])
    "/default/slackBot"
    (callback nil
              #js {:statusCode 200
                   ;; For the Slack API check
                   ;; TODO (low priority) https://api.slack.com/authentication/verifying-requests-from-slack
                   :body (j/get (.parse js/JSON (j/get event :body)) :challenge)})))


(comment
  (def dummy-event
    #js {:version "2.0", :routeKey "ANY /slackBot", :rawPath "/default/slackBot", :rawQueryString "", :headers #js {:accept "*/*", :accept-encoding "gzip,deflate", :content-length 604, :content-type "application/json", :host "4jmgrrysk7.execute-api.eu-central-1.amazonaws.com", :user-agent "Slackbot 1.0 (+https://api.slack.com/robots)", :x-amzn-trace-id "Root=1-5eb04251-dff473b7a8d14a51a0dca648", :x-forwarded-for "54.174.192.196", :x-forwarded-port 443, :x-forwarded-proto "https", :x-slack-request-timestamp 1588609617, :x-slack-signature "v0=d846b125b481013842b7e460145f564291455d79ba302acc17bdcad61b762b02"}, :requestContext #js {:accountId 579644408564, :apiId "4jmgrrysk7", :domainName "4jmgrrysk7.execute-api.eu-central-1.amazonaws.com", :domainPrefix "4jmgrrysk7", :http #js {:method "POST", :path "/default/slackBot", :protocol "HTTP/1.1", :sourceIp "54.174.192.196", :userAgent "Slackbot 1.0 (+https://api.slack.com/robots)"}, :requestId "MA9MyiRtFiAEJPQ=", :routeKey "ANY /slackBot", :stage "default", :time "04/May/2020:16:26:57 +0000", :timeEpoch 1588609617791},
         :body "{\"token\":\"2GI5dHsNabxffKscvxK3nBwh\",\"team_id\":\"T010MGVT4TV\",\"api_app_id\":\"A010P0KP6SV\",\"event\":{\"client_msg_id\":\"756c2148-b72c-45fc-b33d-50309a2b9f49\",\"type\":\"app_mention\",\"text\":\"<@U010MH3GSKD> test\",\"user\":\"U012E480NTB\",\"ts\":\"1588609616.000200\",\"team\":\"T010MGVT4TV\",\"blocks\":[{\"type\":\"rich_text\",\"block_id\":\"rGa\",\"elements\":[{\"type\":\"rich_text_section\",\"elements\":[{\"type\":\"user\",\"user_id\":\"U010MH3GSKD\"},{\"type\":\"text\",\"text\":\" test\"}]}]}],\"channel\":\"C0121SEV6Q2\",\"event_ts\":\"1588609616.000200\"},\"type\":\"event_callback\",\"event_id\":\"Ev0130HRS518\",\"event_time\":1588609616,\"authed_users\":[\"U010MH3GSKD\"]}", :isBase64Encoded false})

  (from-slack? dummy-event) ;; true
  
  (.parse js/JSON (j/get dummy-event :body))
  #js {:token "2GI5dHsNabxffKscvxK3nBwh",
       :team_id "T010MGVT4TV",
       :api_app_id "A010P0KP6SV",
       :type "event_callback",
       :event_id "Ev0130HRS518",
       :event_time 1588609616,
       :authed_users #js ["U010MH3GSKD"]
       :event #js {:client_msg_id "756c2148-b72c-45fc-b33d-50309a2b9f49",
                   :type "app_mention",
                   :text "<@U010MH3GSKD> test",
                   :user "U012E480NTB",
                   :ts "1588609616.000200",
                   :team "T010MGVT4TV",
                   :blocks #js [#js {:type "rich_text",
                                     :block_id "rGa",
                                     :elements #js [#js {:type "rich_text_section",
                                                         :elements #js [#js {:type "user", :user_id "U010MH3GSKD"}
                                                                        #js {:type "text", :text " test"}]}]}],
                   :channel "C0121SEV6Q2",
                   :event_ts "1588609616.000200"}}
  
  (j/get-in dummy-event [:headers :user-agent])

  (j/get-in dummy-event [:requestContext :http :path])
  
  (j/get dummy-event :body)
  
  (js-keys js/process.env)
  #js ["CAML_LD_LIBRARY_PATH" "MANPATH" "TERM_PROGRAM" "NVM_CD_FLAGS" "TERM" "SHELL" "CLICOLOR" "TMPDIR" "PERL5LIB" "GOOGLE_APPLICATION_CREDENTIALS" "DATA_WORLD_API_TOKEN" "TERM_PROGRAM_VERSION" "JAVA_11_HOME" "TERM_SESSION_ID" "LC_ALL" "OCAML_TOPLEVEL_PATH" "NVM_DIR" "USER" "COMMAND_MODE" "SSH_AUTH_SOCK" "__CF_USER_TEXT_ENCODING" "BASH_SILENCE_DEPRECATION_WARNING" "PAGER" "LSCOLORS" "OPAMUTF8MSGS" "FTP_PASSIVE" "CLOJURESCRIPT_HOME" "PATH" "MY_DATOMIC_USERNAME" "LaunchInstanceID" "GH_TUFTE_GIST_AUTH_TOKEN" "PWD" "JAVA_HOME" "EDITOR" "LANG" "ITERM_PROFILE" "XPC_FLAGS" "URUK_TEST_IMG_PATH" "RBENV_SHELL" "XPC_SERVICE_NAME" "PYENV_SHELL" "SHLVL" "HOME" "COLORFGBG" "GOROOT" "LANGUAGE" "LC_TERMINAL_VERSION" "ITERM_SESSION_ID" "LOGNAME" "MY_DATOMIC_PASSWORD" "LC_CTYPE" "CLOJURESCRIPT" "NVM_BIN" "GOPATH" "XCC_HOME" "LC_TERMINAL" "DISPLAY" "JAVA_8_HOME" "SECURITYSESSIONID" "COLORTERM" "_" "OLDPWD"]

  (j/get js/process.env :MANPATH)
  
  )
