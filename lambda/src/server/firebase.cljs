(ns server.firebase
  (:require [clojure.string :as str]
            [kitchen-async.promise :as p]
            [lambdaisland.uri :as uri]
            [server.common :as common]
            [server.http :as http]))

(defn json-url [path & [{:keys [query]}]]
  (doto (str (:databaseURL common/firebase-app-config)
             (str/replace-first path #"^/*" "/")            ;; needs a single leading "/"
             ".json"
             "?"
             (-> query
                 (common/update-some {:orderBy common/clj->json
                                      :startAt common/clj->json
                                      :endAt common/clj->json
                                      :equalTo common/clj->json})
                 (assoc :auth common/firebase-database-secret)
                 uri/map->query-string))
    (->> (prn :json-url))))

(defn get+ [path & [opts]]
  (http/fetch-json+ (json-url path opts)
                    {:method "GET"}))

(defn put+ [path & [opts]]
  (http/fetch-json+ (json-url path opts)
                    (clj->js {:method "PUT"
                              :body (some-> (:body opts) common/clj->json)})))

(defn post+ [path & [opts]]
  (http/fetch-json+ (doto (json-url path opts) prn)
                    {:method "POST"
                     :body (some-> (:body opts) common/clj->json)}))

(defn map->list [id-key m]
  (reduce-kv (fn [out key value] (conj out (assoc value id-key (name key)))) [] m))

(defn obj->list [id-key m]
  (map->list id-key (js->clj m :keywordize-keys true)))
