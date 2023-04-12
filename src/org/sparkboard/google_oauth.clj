(ns org.sparkboard.google-oauth
  "Minimum viable Google login with OAuth.

  Originally a Clojure port of ScribeJava example: https://github.com/scribejava/scribejava/blob/master/scribejava-apis/src/test/java/com/github/scribejava/apis/examples/Google20Example.java"
  (:import (com.github.scribejava.core.builder ServiceBuilder)
           (com.github.scribejava.apis GoogleApi20)
           (com.github.scribejava.core.model OAuthRequest)
           (com.github.scribejava.core.model Verb))
  (:require [clojure.java.browse]
            [clojure.string :as str]
            [jsonista.core :as json]
            [org.sparkboard.server.env :as env]))

;;;; Mandatory setup
;; * `:oauth/client-id` & `:oauth/client-secret` config vars
;; * GCP project with callback URL

(def protected-resource-url
  "https://www.googleapis.com/oauth2/v3/userinfo")

(def secret-state
  (str "secret" (rand-int 999999)))

(def svc
  (.build (doto (ServiceBuilder. (env/config :oauth.google/client-id))
            (.apiSecret (env/config :oauth.google/client-secret))
            ;; space-separated strings identifying individual scopes:
            (.defaultScope "profile openid email")
            ;; the following URL must be explicitly allowed in the GCP project
            (.callback "http://localhost:3000/auth/handler")) ;; FIXME
          (GoogleApi20/instance)))

;; https://developers.google.com/identity/protocols/OAuth2WebServer#preparing-to-start-the-oauth-20-flow

(def auth-url
  (.build (doto (.createAuthorizationUrlBuilder svc)
            (.state secret-state)
            (.additionalParams {"access_type" "offline"
                                ;; force to reget refresh token (if user are asked not the first time)
                                "prompt" "consent"}))))

(comment
 ;; In your browser, go through the auth flow:
 (clojure.java.browse/browse-url auth-url)

 )

;; --> verify that `state` in the redirected URL is equal to `secret-state` above

;; (assert (= secret-state TODO)
;;         "State mismatch; possible security attack")


;; --> extract auth `code` from the redirected URL and put it below in `auth-code`

(defn url->auth-code
  "Naive helper fn. Extracts auth code from full URl."
  [s]
  (let [s' (subs s (+ 5 (.indexOf s "code=")))]
    (-> s'
        (subs 0 (.indexOf s' "&"))
        ;; more robust URL decoding would be nice, but this works:
        (str/replace "%2F" "/"))))

(comment  
  ;; a dummied sample URL, not valid:
  (url->auth-code "http://localhost:3000/auth/handler?state=secret20720&code=4%2F0AVHEzk56jO4oO1Q4YfqjqiNrOHzOEsq5I7q8DFM9hwG0kBnn7PRgSq1ZUp5N2fmfLjai2w&scope=email+profile+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fuserinfo.profile+openid+https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fuserinfo.email&authuser=0&prompt=consent")
  ;; => "4/0BBBEtk6Qd8BYAU5vKMTGtZmGcO3pJbt2Cj66rwbRQXdgFkkFCvZ5kDnViuJvTiNzXwGs0h"
  ;; (copy-paste that into `auth-code`)
  
  )

(def auth-code
  ;; a sample, not valid:
  "4/0AVHEzk56jO4oO1Q4YfqjqiNrOHzOEsq5I7q8DFM9hwG0kBnn7PRgSq1ZUp5N2fmfLjai2w")

(def access-token
  ;; this is defined outside the below `let` so it fails early in the case of
  ;; auth code mistake
  (try (.getAccessToken svc auth-code)
       (catch com.github.scribejava.core.model.OAuth2AccessTokenErrorResponse oa2ater
         oa2ater)))

(comment
  ;; in `comment` to avoid exception-throwing HTTP calls when not desired
  (def response
    (let [request (OAuthRequest. Verb/GET
                                 ;; following can take additional "?fields=" but
                                 ;; we didn't implement it
                                 protected-resource-url)
          ;; spooky side effecty API :/
          _ (.signRequest svc
                          access-token
                          request)]
      (.execute svc request)))
  
 (.getCode response) ;; you want 200

 (json/read-value (.getBody response)) ;; should be a map with keys like
 ;; "given_name", "locale", "picture",
 ;; "sub", usw.

 )
