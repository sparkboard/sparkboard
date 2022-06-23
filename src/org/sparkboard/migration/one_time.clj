(ns org.sparkboard.migration.one-time
  (:require [clojure.java.shell :refer [sh]]
            [jsonista.core :as json]
            [org.sparkboard.server.env :as env]))

;; For exploratory or one-off migration steps.
;;
;; The `mongoexport` command is required.
;; MacOS: brew tap mongodb/brew && brew install mongodb-community
;; Alpine: apk add --update-cache mongodb-tools
;;

(def LOCAL-DIR (do (sh "mkdir" "-p" "./.migration-data")
                   "./.migration-data"))

(def MONGODB_URI (-> env/config :prod :mongodb/readonly-uri))

(def collections {"discussion" "discussionschemas"
                  "notification" "notificationschemas"
                  "user" "users"
                  "thread" "threadschemas"
                  "project" "projectschemas"})

(defn fetch-mongodb []
  (doseq [[coll-name mongo-coll] collections
          :let [{:keys [out err exit]} (sh "mongoexport"
                                           "--uri" MONGODB_URI
                                           "--jsonArray"
                                           "--collection" mongo-coll)]]
    (when-not (zero? exit)
      (throw (Exception. err)))
    (spit (str LOCAL-DIR "/" coll-name ".edn")
          (json/read-value out))))

(defn read-mongodb []
  (->> (keys collections)
       (reduce (fn [m k]
                 (let [v (read-string (slurp (str LOCAL-DIR "/" k ".edn")))]
                   (assoc m k v))) {})))

(defn fetch-firebase []
  (let [{token :firebase/database-secret
         {db :databaseURL} :firebase/app-config} (:prod env/config)]
    (-> (str db "/.json?auth=" token)
        (slurp)
        (json/read-value)
        (->> (spit (str LOCAL-DIR "/firebase.edn"))))))

(defn read-firebase []
  (->> (slurp (str LOCAL-DIR "/firebase.edn")) (read-string)))

(comment

 ;; download mongodb copy
 (time (fetch-mongodb))
 ;; Elapsed time: 30082.72146 msecs
 (:out (sh "ls" LOCAL-DIR))

 (->> (read-mongodb) (reduce-kv (fn [m k v] (assoc m k (count v))) {}))
 ;; {"discussion" 17645,
 ;;  "notification" 25521,
 ;;  "user" 33463,
 ;;  "thread" 5415,
 ;;  "project" 7776}

 ;; download firebase copy
 (time (fetch-firebase))
 ;; Elapsed time: 1604.601176 msecs

 (->> (read-firebase) (reduce-kv (fn [m k v] (assoc m k (count v))) {}))
 ;; {"org" 5,
 ;;  "_parent" 5,
 ;;  "settings" 365,
 ;;  "collection" 1,
 ;;  "feedback" 37,
 ;;  "invalidations" 4,
 ;;  "domain" 374,
 ;;  "privateSettings" 39,
 ;;  "roles" 2}
 )

