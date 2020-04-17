(ns org.sparkboard.server.slack
  (:require [clj-http.client :as client]
            [jsonista.core :as json]
            [org.sparkboard.env :as env]))

(defn web-api [family method body]
  (client/post (str "https://slack.com/api/" (name family) "." (name method))
               {:content-type "application/json; charset=utf-8"
                :headers {"Authorization" (str "Bearer " (-> env/get :slack.app :bot-user-oauth-token))}
                :body (json/write-value-as-string body)}))

;; FIXME
;; (defn invite-to-channel! [user channel]
;;   (web-api :conversations :invite))

(defn create-channel! [channel]
  ;; https://api.slack.com/methods/conversations.create
  (web-api :conversations :create {:name channel}))

;; TODO automatically add users

(comment
  (create-channel! "is-this-thing-on")

  ;; TODO delete/archive channel, for testing and clean-up
  )
