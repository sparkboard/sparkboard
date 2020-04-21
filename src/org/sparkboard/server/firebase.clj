(ns org.sparkboard.server.firebase
  (:require [clojure.string :as str]
            [jsonista.core :as json]
            [org.sparkboard.env :as env]))

(defn to-json
  "Parse json string, with keyword-keys"
  [s]
  (json/read-value s (json/object-mapper {:decode-key-fn true})))

(def database-url (:databaseURL (to-json (env/get :firebase/app-config))))
(def database-secret (env/get :firebase/database-secret))

(defn json-path [path]
  (str database-url
       (str/replace-first path #"^\/*" "/")                 ;; needs a single leading "/"
       ".json"
       "?auth=" database-secret))

(def fetch-json (comp to-json slurp json-path))

(comment

  (:title (fetch-json "settings/demo"))

  )