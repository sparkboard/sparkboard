(ns org.sparkboard.server
  "HTTP server handling all requests
   * slack integration
   * synced queries over websocket"
  (:gen-class)
  (:require [bidi.ring :as bidi.ring]
            [clojure.string :as str]
            [bidi.bidi :as bidi]
            [hiccup.util]
            [mhuebert.cljs-static.html :as html]
            [muuntaja.core :as m]
            [org.httpkit.server :as httpkit]
            [org.sparkboard.datalevin :as datalevin :refer [conn]]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.firebase.tokens :as fire-tokens]
            [org.sparkboard.log]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.server.impl :as impl]
            [org.sparkboard.server.nrepl :as nrepl]
            [org.sparkboard.slack.server :as slack.server]
            [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.query :as q]
            [re-db.reactive :as r]
            [re-db.read :as read]
            [re-db.sync :as sync]
            [re-db.xform :as xf]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.util.mime-type :as ring.mime-type]
            [ring.util.request]
            [ring.util.response :as ring.response]
            [taoensso.timbre :as log]
            [muuntaja.core :as muu]
            [muuntaja.middleware :as muu.middleware]
            [tools.sparkboard.transit :as transit]
            [re-db.sync.transit :as re-db.transit]
            [org.sparkboard.websockets :as ws]
            [org.sparkboard.server.resources :as res]
            [org.sparkboard.routes :as routes])
  (:import [java.time Instant]))

(comment
 (fire-tokens/decode token))

(def muuntaja (muu/create
               (-> m/default-options
                   ;; set Transit readers & writers
                   (update-in [:formats "application/transit+json"]
                              merge {:decoder-opts {:handlers re-db.transit/read-handlers}
                                     :encoder-opts {:handlers re-db.transit/write-handlers}}))))

(def spa-page
  (memoize
   (fn [config]
     (-> {:body (str (html/html-page {:title "Sparkboard"
                                      :styles [{:href "https://unpkg.com/tachyons@4.10.0/css/tachyons.min.css"}]
                                      :scripts/body [{:src (str "/js/compiled/app.js?v=" (.toEpochMilli (Instant/now)))}]
                                      :body [[:script#SPARKBOARD_CONFIG {:type "application/transit+json"}
                                              (hiccup.util/raw-string (transit/write config))]
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

(def wrap-index-fallback
  ;; fall back to index.html -- TODO: re-use the client router to serve 404s for unknown paths
  (fn [f]
    (fn [req]
      (or (f req)
          (spa-page env/client-config)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Websockets

;; wrap a `transact!` function to call `read/handle-report!` afterwards, which will
;; cause dependent queries to re-evaluate.
(defn transact! [txs]
  (->> (re-db.api/transact! datalevin/conn txs)
       (read/handle-report! datalevin/conn)))

(memo/defn-memo $resolve-ref
  "Resolves a path-based ref"
  [routes path]
  (when-let [{:keys [handler route-params]}
             (bidi/match-route routes path)]
    (handler route-params)))

(defn ref-handler
  "Serve refs at the given routes. If a match is found, return the ref.
  If request is for text/html, serve single-page-response instead."
  [{:keys [routes html-response]}]
  (fn [{:keys [uri path-info] :as req}]
    (when-let [ref ($resolve-ref routes (or path-info uri))]
      (or (when (str/includes? (get-in req [:headers "accept"]) "text/html")
            html-response)
          {:status 200 :body @ref}))))

(def app
  (-> (impl/join-handlers
       slack.server/handler
       (ws/handler "/ws" {:handlers (merge
                                     {:conj! (fn [_] (swap! res/!list conj (rand-int 100)))}
                                     (sync/query-handlers
                                      (fn [query]
                                        ($resolve-ref routes/server-routes query ))))})
       (-> (ref-handler {:routes routes/server-routes
                         :html-response (spa-page env/client-config)})
           (muu.middleware/wrap-format muuntaja)))
      wrap-index-fallback
      wrap-handle-errors
      wrap-static-first
      #_wrap-debug-request))

(defonce the-server (atom nil))

(defn stop-server! []
  (some-> @the-server (httpkit/server-stop!))
  (nrepl/stop!))

(defn restart-server!
  "Setup fn.
  Starts HTTP server, stopping existing HTTP server first if necessary."
  [port]
  (stop-server!)
  (reset! the-server (httpkit/run-server #'app {:port port
                                                :legacy-return-value? false}))
  (when (not= "dev" (env/config :env)) ;; using shadow-cljs server in dev
    (nrepl/start!)))

(defn -main []
  (log/info "Starting server" {:jvm (System/getProperty "java.vm.version")})
  (fire-jvm/sync-all) ;; cache firebase db locally
  (restart-server! (or (some-> (System/getenv "PORT") (Integer/parseInt))
                       3000)))

(comment ;;; Intensive request debugging
 (def !requests (atom []))

 (defn wrap-debug-request [f]
   (fn [req]
     (log/info :request req)
     (swap! !requests conj req)
     (f req)))

 @!requests

 )

(comment ;;; Webserver control panel
 (-main)

 (let [srvr @the-server]
   {:port (httpkit/server-port @the-server)
    :status (httpkit/server-status @the-server)})

 #_(reset! the-server nil)

 (restart-server! 3000)

 )
