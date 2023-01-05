(ns org.sparkboard.server
  "HTTP server handling Slack requests"
  (:gen-class)
  (:require [bidi.ring :as bidi.ring]
            [hiccup.util :refer [raw-string]]
            [mhuebert.cljs-static.html :as html]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.firebase.tokens :as fire-tokens]
            [org.sparkboard.log]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.server.impl :as impl]
            [org.sparkboard.server.nrepl :as nrepl]
            [org.sparkboard.slack.server :as slack.server]
            [ring.adapter.jetty :refer [run-jetty]]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.util.mime-type :as ring.mime-type]
            [ring.util.request]
            [ring.util.response :as ring.response]
            [taoensso.timbre :as log]
            [tools.sparkboard.transit :as transit]))

(comment
  (fire-tokens/decode token))

(def spa-page
  (memoize
    (fn [config]
      (-> {:body
           (str (html/html-page {:title "Sparkboard"
                                 :styles [{:href "https://unpkg.com/tachyons@4.10.0/css/tachyons.min.css"}]
                                 :scripts/body [{:src (str "/js/compiled/app.js?v=" (.getTime (java.util.Date.)))}]
                                 :body [[:script#SPARKBOARD_CONFIG {:type "application/transit+json"}
                                         (raw-string (transit/write config))]
                                        [:div#web]]}))}
          (ring.response/content-type "text/html")
          (ring.response/status 200)))))

(defn wrap-handle-errors [f]
  (fn [req]
    (log/info :URI (:uri req))
    (try (f req)
         (catch Exception e
           (if (env/config :dev.logging/tap?)
             (log/error (ex-message e) e)
             (log/error (ex-message e)
                        (ex-data e)
                        (ex-cause e)))
           (impl/return-text
            (:status (ex-data e) 500)
            (ex-message e)))
         (catch java.lang.AssertionError e
           (if (env/config :dev.logging/tap?)
             (log/error (ex-message e) e)
             (log/error (ex-message e)
                        (ex-data e)
                        (ex-cause e)))
           (impl/return-text
            (:status (ex-data e) 500)
            (ex-message e))))))

(defn public-resource [path]
  (some-> (ring.response/resource-response path {:root "public"})
          (ring.response/content-type (ring.mime-type/ext-mime-type path))))

(defn wrap-static-first [f]
  ;; serve static files before all the other middleware, logging, etc.
  (fn [req]
    (or (public-resource (:uri req))
        (f req))))

(def wrap-static-fallback
  ;; fall back to index.html -- TODO: re-use the client router to serve 404s for unknown paths
  (fn [f]
    (fn [req]
      (or (f req)
          (spa-page env/client-config)))))

(def app
  (-> (bidi.ring/make-handler ["/" slack.server/routes])
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)
      (ring.middleware.format/wrap-restful-format {:formats [:json-kw :transit-json]})
      slack.server/wrap-slack-verify
      wrap-static-fallback
      wrap-handle-errors
      wrap-static-first))

(defonce server (atom nil))

(defn stop-server! []
  (some-> @server (.stop))
  (nrepl/stop!))

(defn restart-server!
  "Setup fn.
  Starts HTTP server, stopping existing HTTP server first if necessary."
  [port]
  (stop-server!)
  (reset! server (run-jetty #'app {:port port :join? false}))
  (when (not= (env/config :env) "dev")                      ;; using shadow-cljs server in dev'
    (nrepl/start!)))




(defn -main []
  (log/info "Starting server" {:jvm (System/getProperty "java.vm.version")})
  (fire-jvm/sync-all)                                       ;; cache firebase db locally
  (restart-server! (or (some-> (System/getenv "PORT") (Integer/parseInt)) 3000)))

(comment
  (-main)

  @server
  
  (restart-server! 3000)
  
  (.shutdown pool)
  (.shutdownNow pool)
  )
