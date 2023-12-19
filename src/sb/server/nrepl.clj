(ns sb.server.nrepl
  (:require [clojure.java.shell :refer [sh]]
            [nrepl.server :as nrepl]
            [sb.server.env :as env]
            [taoensso.timbre :as log])
  (:import (java.util.concurrent Executors TimeUnit)))

(defonce !nrepl-server (atom nil))
(defonce thread-pool (Executors/newScheduledThreadPool 1))

(defn every! [delay interval unit f]
  (.scheduleAtFixedRate thread-pool f delay interval (case unit :ms TimeUnit/MILLISECONDS
                                                                :seconds TimeUnit/SECONDS
                                                                :minutes TimeUnit/MINUTES
                                                                :hours TimeUnit/HOURS
                                                                :days TimeUnit/DAYS)))

(defn stop-shutdown-check! []
  (some-> (:shutdown-check @!nrepl-server) (.cancel false)))

(defn start-shutdown-check! []
  ;; shutdown staging server when nobody is listening
  (when (= (env/config :env) "staging")
    (stop-shutdown-check!)
    (swap! !nrepl-server assoc
           :shutdown-check (every! 0 30 :minutes
                                   #(when (= 0 (-> @!nrepl-server :open-transports deref count))
                                      (sh "bb" "fly:down"))))))

(defn stop! []
  (when-let [server @!nrepl-server]
    (nrepl/stop-server server)
    (stop-shutdown-check!)
    (reset! !nrepl-server nil)))

(defn start! []
  (stop!)
  (let [nrepl-port 7888
        nrepl-host "::"]
    (log/info "Starting nrepl server" {:port nrepl-port :host nrepl-host})
    (reset! !nrepl-server (nrepl/start-server :bind nrepl-host :port nrepl-port))
    (start-shutdown-check!)))

(comment
 (sh "bb")
 (start!))