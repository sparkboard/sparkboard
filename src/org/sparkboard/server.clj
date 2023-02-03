(ns org.sparkboard.server
  "HTTP server handling all requests
   * slack integration
   * synced queries over websocket"
  (:gen-class)
  (:require [clojure.string :as str]
            [hiccup.util]
            [muuntaja.core :as m]
            [muuntaja.core :as muu]
            [muuntaja.middleware :as muu.middleware]
            [org.httpkit.server :as httpkit]
            [org.sparkboard.datalevin :as datalevin]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.firebase.tokens :as fire-tokens]
            [org.sparkboard.log]
            [org.sparkboard.routes :as routes]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.server.impl :as impl]
            [org.sparkboard.server.nrepl :as nrepl]
            [org.sparkboard.server.views :as server.views]
            [org.sparkboard.slack.server :as slack.server]
            [org.sparkboard.websockets :as ws]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.read :as read]
            [re-db.sync :as sync]
            [re-db.sync.entity-diff-1 :as sync.entity]
            [re-db.sync.transit :as re-db.transit]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.util.http-response :as ring.http]
            [ring.util.mime-type :as ring.mime-type]
            [ring.util.request]
            [ring.util.response :as ring.response]
            [taoensso.timbre :as log]))

(comment
 (fire-tokens/decode token))

(def muuntaja
  ;; Note: the `:body` BytesInputStream will be present but decode/`slurp` to an
  ;; empty String if no read format is declared for the request's content-type.
  (muu/create (-> m/default-options
                  ;; set Transit readers & writers
                  (update-in [:formats "application/transit+json"]
                             merge {:decoder-opts {:handlers re-db.transit/read-handlers}
                                    :encoder-opts {:handlers re-db.transit/write-handlers}}))))

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
          (server.views/spa-page env/client-config)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Websockets

;; wrap a `transact!` function to call `read/handle-report!` afterwards, which will
;; cause dependent queries to re-evaluate.
(defn transact! [txs]
  (read/transact! datalevin/conn txs))

#_(memo/clear-memo! $resolve-ref)

(defn http-handler [{:as req :keys [path-info uri]}]
  (let [path (or path-info uri)
        {:as match :keys [query mutation params]} (routes/match-route path)
        html? (str/includes? (get-in req [:headers "accept"]) "text/html")
        method (:request-method req)]
    (when (and match (not html?))
      (cond (and (= method :post) mutation)
            (some-> (apply mutation {:request req} params (:body-params req))
                    ring.http/ok)

            (and (= method :get) query)
            (some-> (query params)
                    deref
                    ring.http/ok)))))

(memo/defn-memo $resolve-query [[id & args :as route-vec]]
  (assert (keyword? id))
  (some-> (get-in @routes/!routes [id :query])
          requiring-resolve
          (apply (or (seq args) [{}]))
          sync.entity/txs
          (r/catch (fn [e]
                     (println "Error in $resolve-query")
                     (println e)
                     {:error (ex-message e)}))))


(def app
  (-> (impl/join-handlers slack.server/handlers
                          (ws/handler "/ws" {:handlers
                                             (sync/query-handlers
                                              (comp $resolve-query routes/path->route))})
                          (-> http-handler (muu.middleware/wrap-format muuntaja)))
      wrap-index-fallback
      wrap-handle-errors
      wrap-static-first
      #_(wrap-debug-request :first)))

(defonce the-server
  (atom nil))

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

 (defn wrap-debug-request [f id]
   (fn [req]
     (log/info :request req)
     (swap! !requests conj (assoc req :debug/id id))
     (f req)))

 (->> @!requests
      (remove :websocket?)
      (map #(select-keys % [:debug/id :body-params :body :content-type]))
      (into []))

 )

(comment ;;; Webserver control panel
 (-main)

 (let [srvr @the-server]
   {:port (httpkit/server-port @the-server)
    :status (httpkit/server-status @the-server)})

 #_(reset! the-server nil)

 (restart-server! 3000)

 )
