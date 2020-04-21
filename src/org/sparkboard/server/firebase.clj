(ns org.sparkboard.server.firebase
  (:require [jsonista.core :as json]
            [org.sparkboard.env :as env]
            [clojure.string :as str]))

(def to-json #(json/read-value % (json/object-mapper {:decode-key-fn true})))

(def database-url (:databaseURL (to-json (env/get :firebase/app-config))))
(def database-secret (env/get :firebase/database-secret))

(defn json-path [path]
  (str database-url
       (str/replace-first path #"^\/*" "/")
       ".json"
       "?auth=" (env/get :firebase/database-secret)))

(def fetch-json (comp to-json slurp json-path))

(comment
  app-config

  (:title (fetch-json "settings/demo"))

  )