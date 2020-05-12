#!bin/bb

(require '[babashka.curl :as curl])

(prn (curl/get "https://www.example.com"))