(ns sparkboard.server.core
  "HTTP server handling all requests
   * slack integration
   * synced queries over websocket
   * mutations over websocket"
  (:gen-class)
  (:require [clj-time.coerce]
            [clojure.string :as str]
            [hiccup.util]
            [muuntaja.core :as m]
            [muuntaja.core :as muu]
            [muuntaja.middleware :as muu.middleware]
            [org.httpkit.server :as httpkit]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.read :as read]
            [re-db.sync :as sync]
            [re-db.sync.entity-diff-1 :as sync.entity]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.middleware.params :as ring.params]
            [ring.util.http-response :as ring.http]
            [ring.util.mime-type :as ring.mime]
            [ring.util.request]
            [ring.util.response :as ring.response]
            [sparkboard.datalevin :as datalevin]
            [sparkboard.impl.server :as impl]
            [sparkboard.log]
            [sparkboard.routes :as routes]
            [sparkboard.server.auth :as auth]
            [sparkboard.server.env :as env]
            [sparkboard.server.html :as server.views]
            [sparkboard.server.nrepl :as nrepl]
            [sparkboard.slack.firebase.jvm :as fire-jvm]
            [sparkboard.slack.server :as slack.server]
            [sparkboard.websockets :as ws]
            [taoensso.timbre :as log]))

(def muuntaja
  ;; Note: the `:body` BytesInputStream will be present but decode/`slurp` to an
  ;; empty String if no read format is declared for the request's content-type.
  (muu/create m/default-options))

(defn wrap-handle-errors [f]
  (fn [req]
    (log/info :URI (:uri req))
    (try (f req)
         (catch Exception e
           (tap> e)
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
          (ring.response/content-type (ring.mime/ext-mime-type path))))

(defn wrap-static-first [f]
  ;; serve static files before all the other middleware, logging, etc.
  (fn [req]
    (or (public-resource (:uri req))
        (f req))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Websockets

;; wrap a `transact!` function to call `read/handle-report!` afterwards, which will
;; cause dependent queries to re-evaluate.
(defn transact! [txs]
  (read/transact! datalevin/conn txs))

#_(memo/clear-memo! $resolve-ref)


(def route-handler
  (-> (fn [{:as req :keys [uri query-string]}]
        (let [uri (str uri (some->> (not-empty query-string) (str "?")))
              {:as match :keys [view query mutation handler params public]} (routes/match-path uri)
              method (:request-method req)
              html? (and (= method :get)
                         (str/includes? (get-in req [:headers "accept"]) "text/html"))
              authed? (:account req)]
          (cond

            (and (not authed?) (not public)) (buddy.auth/throw-unauthorized)

            handler (handler req params)

            ;; mutation fns are expected to return HTTP response maps
            (and mutation (= method :post)) (apply mutation req params (:body-params req))

            (and html? (or query view)) (server.views/single-page-html env/client-config (:account req))

            query (some-> (query params) deref ring.http/ok)

            ;; query fns return reactions which must be wrapped in HTTP response maps
            :else (ring.http/not-found "Not found"))))
      #_(muu.middleware/wrap-format muuntaja)))

(memo/defn-memo $txs [ref]
  (r/catch (sync.entity/txs ref)
           (fn [e]
             (println "Error in $resolve-query")
             (println e)
             {:error (ex-message e)})))

(defn resolve-query [path-or-route]
  (let [{:keys [route query]} (routes/match-path path-or-route)
        [id & args] route]
    (some-> query
            deref
            (apply (or (seq args) [{}]))
            $txs)))

(def ws-options {:handlers (sync/query-handlers resolve-query)})

(def handler
  (-> (impl/join-handlers #(#'ws/handle-ws-request ws-options %)
                          slack.server/handlers
                          #'route-handler)
      auth/wrap-auth
      ring.params/wrap-params
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
  (reset! the-server (httpkit/run-server #'handler {:port port
                                                    :legacy-return-value? false}))
  (when (not= "dev" (env/config :env)) ;; using shadow-cljs server in dev
    (nrepl/start!)))

(defn -main []
  (log/info "Starting server" {:jvm (System/getProperty "java.vm.version")})
  (fire-jvm/sync-all) ;; cache firebase db locally
  (restart-server! (or (some-> (System/getenv "PORT") (Integer/parseInt))
                       3000)))

(comment ;;; Webserver control panel
 (-main)

 (restart-server! 3000))
