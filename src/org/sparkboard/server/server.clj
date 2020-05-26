(ns org.sparkboard.server.server
  (:require [bidi.ring]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.util.http-response :as http]))

(defonce server (atom nil))

(defn stop-server! []
  (when-not (nil? @server)
    (.stop @server)))

(def routes
  ["/index" (fn [req] (http/ok {:hello "is it me you're looking for?"}))])

(def app
  (-> (bidi.ring/make-handler routes)
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)
      (ring.middleware.format/wrap-restful-format :formats [:edn])))

(defn restart-server!
  "Setup fn.
  Starts HTTP server, stopping existing HTTP server first if necessary."
  [port]
  (stop-server!)
  (reset! server (run-jetty #'app {:port port :join? false})))

(comment
  (restart-server! 3000)
   
  @server

  (app {:uri "/index"})

  ;; See also `dev.restlient` at root
  )
