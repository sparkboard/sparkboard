(ns org.sparkboard.migration.one-time
  (:require [clojure.java.shell :refer [sh]]
            [jsonista.core :as json]
            [org.sparkboard.env :as env]))

;; For exploratory or one-off migration steps.
;;
;; The `mongoexport` command is required. To install mongodb-community on MacOS:
;;
;;  brew tap mongodb/brew && brew install mongodb-community
;;

(def LOCAL-DIR "./.migration-data")

(defn fetch-mongodb []
  (->> {"discussionschemas" "discussion"
        "notificationschemas" "notification"
        "users" "user"
        "threadschemas" "thread"
        "projectschemas" "project"}
       (reduce (fn [m [mongo-coll dest-name]]
                 (let [{:keys [out err exit]}
                       (sh "mongoexport"
                           "--uri" (:mongodb-uri (env/get :one-time/staging))
                           "--jsonArray"
                           "--collection" mongo-coll)]
                   (println err)
                   (if-not (zero? exit)
                     (throw (Exception. err))
                     (assoc m dest-name (json/read-value out))))) {})
       (spit (str LOCAL-DIR "/mongodb.edn"))))

(defn read-mongodb []
  (->> (slurp (str LOCAL-DIR "/mongodb.edn")) (read-string)))

(defn fetch-firebase []
  (let [{:firebase/keys [db token]} (env/get :one-time/staging)]
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

