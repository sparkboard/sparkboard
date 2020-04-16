(ns org.sparkboard.server.crux
  (:require [clojure.string :as str]
            [crux.api :as crux]
            [mount.core :as mo]
            [org.bovinegenius.exploding-fish :as uri]
            [org.sparkboard.env :as env]))

(defn parse-db-url [url]
  (let [{:keys [host path user-info]} (uri/uri url)
        [user password] (some-> user-info (str/split ":"))
        db-name (subs path 1)]
    {:db-name db-name
     :host host
     :user user
     :password password}))

(def default-opts
  (let [{:keys [db-name host user password]} (-> env/get :postgres/database-url parse-db-url)]
    {:crux.node/topology '[crux.jdbc/topology]

     ;:crux.node/kv-store 'crux.kv.memdb/kv
     ;:crux.kv/db-dir "data/db-dir-1"

     :crux.jdbc/dbtype "postgresql"
     :crux.jdbc/dbname db-name
     :crux.jdbc/host host
     :crux.jdbc/user user
     :crux.jdbc/password password}))

(mo/defstate node
  :start (crux/start-node default-opts)
  :stop (.close node))

(comment
  (mo/start #'node)

  (crux/db node)

  (crux/submit-tx node [[:crux.tx/put
                         ;; valid ids: https://opencrux.com/docs#transactions-valid-ids
                         {:crux.db/id :demo/user
                          :account/email "hello@example.com"}]])

  (crux/entity (crux/db node) :demo/user))