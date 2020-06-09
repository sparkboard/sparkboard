(ns repl.sparkboard-receiver-server
  ;; NB: run in a *separate* JVM/REPL
  (:require [bidi.ring]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.util.http-response :as http]
            [taoensso.timbre :as log]))

(defonce server (atom nil))

(defn stop-server! []
  (when-not (nil? @server)
    (.stop @server)))

#_
(def routes
  ["/" (fn [req] (let [rsp {:now-timestamp (System/currentTimeMillis)
                           :params-timestamp (-> req :params :debug-timestamp)}]
                  (log/info "[RECEIVER SERVER]" rsp)
                  (http/ok rsp)))])

(def app
  (-> #_(bidi.ring/make-handler routes)
      (fn [req] (let [now-ts (System/currentTimeMillis)
                     start-ts (-> req :params :debug-timestamp Long/parseLong)]
                 (spit "debug-server.log" (str (- now-ts start-ts) "\n")
                       :append true)
                 (http/ok)))
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)
      (ring.middleware.format/wrap-restful-format :formats [:json])))

(defn restart-server!
  "Setup fn.
  Starts HTTP server, stopping existing HTTP server first if necessary."
  [port]
  (stop-server!)
  (reset! server (run-jetty #'app {:port port :join? false})))

(comment
  (restart-server! 4000)
   
  @server

  (app {:uri "/index"})

  (spit (str "debug-server." (System/currentTimeMillis))
        {:a "b"})
  )
