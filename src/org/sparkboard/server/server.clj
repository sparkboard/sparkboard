(ns org.sparkboard.server.server
  "HTTP server handling Slack requests"
  (:require [bidi.ring]
            [org.sparkboard.server.handle :as handle]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [taoensso.timbre :as log]))

(defonce server (atom nil))

(defn stop-server! []
  (when-not (nil? @server)
    (.stop @server)))

(def routes
  ;; TODO provide separate URLs where Slack allows, so we can pass to
  ;; individual handlers here rather than by examining the request
  ["/" (fn [x] (#'handle/incoming x))])

(def app
  (-> (bidi.ring/make-handler routes)
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)
      (ring.middleware.format/wrap-restful-format {:formats [:json-kw]})))

(defn restart-server!
  "Setup fn.
  Starts HTTP server, stopping existing HTTP server first if necessary."
  [port]
  (stop-server!)
  (reset! server (run-jetty #'app {:port port :join? false})))

(comment
  (restart-server! 3000)

  @server

  ;; See also `dev.restlient` at project root
  )
