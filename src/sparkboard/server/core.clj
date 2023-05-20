(ns sparkboard.server.core
  "HTTP server handling all requests
   * slack integration
   * synced queries over websocket
   * mutations over websocket"
  (:gen-class)
  (:require [clj-time.coerce]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [hiccup.util]
            [markdown.core :as md]
            [muuntaja.core :as m]
            [muuntaja.core :as muu]
            [muuntaja.middleware :as muu.middleware]
            [org.httpkit.server :as httpkit]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.read :as read]
            [re-db.sync :as sync]
            [re-db.sync.entity-diff-1 :as sync.entity]
            [ring.middleware.cookies :as ring.cookies]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.util.http-response :as ring.http]
            [ring.util.mime-type :as ring.mime]
            [ring.util.request]
            [ring.util.response :as ring.response]
            [sparkboard.datalevin :as datalevin]
            [sparkboard.impl.server :as impl]
            [sparkboard.log]
            [sparkboard.routes :as routes]
            [sparkboard.schema]
            [sparkboard.server.accounts :as accounts]
            [sparkboard.server.env :as env]
            [sparkboard.server.html :as server.html]
            [sparkboard.server.nrepl :as nrepl]
            [sparkboard.slack.firebase.jvm :as fire-jvm]
            [sparkboard.slack.server :as slack.server]
            [sparkboard.websockets :as ws]
            [taoensso.timbre :as log]))

(def muuntaja
  ;; Note: the `:body` BytesInputStream will be present but decode/`slurp` to an
  ;; empty String if no read format is declared for the request's content-type.
  (muu/create m/default-options))

(defn wrap-log
  "Log requests (log/info) and errors (log/error)"
  [f]
  (let [handle-e (fn [e]
                   (if (env/config :dev.logging/tap?)       ;; configured in .local.config.edn
                     (log/error (ex-message e) e)
                     (log/error (ex-message e)
                                (ex-data e)
                                (ex-cause e)))
                   (let [{:as data :keys [wrap-response] :or {wrap-response identity}} (ex-data e)]
                     (-> (server.html/error e data)
                         wrap-response)))]
    (fn [req]
      (log/info :req req)
      (log/info :URI (:uri req))
      (try (let [res (f req)]
             (log/info :res res)
             res)
           (catch Exception e (handle-e e))
           (catch java.lang.AssertionError e (handle-e e))))))

(defn serve-static
  "Serve files from `public-dir` with content-type derived from file extension."
  [public-dir]
  (fn [{:as req :keys [uri]}]
    (some-> (ring.response/resource-response uri {:root public-dir})
            (ring.response/content-type (ring.mime/ext-mime-type uri)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Websockets

;; wrap a `transact!` function to call `read/handle-report!` afterwards, which will
;; cause dependent queries to re-evaluate.
(defn transact! [txs]
  (read/transact! datalevin/conn txs))

#_(memo/clear-memo! $resolve-ref)

(defn serve-markdown [_ {:keys [file/name]}]
  (server.html/formatted-text (md/md-to-html-string (slurp (io/resource (str "documents/" name ".md"))))))

(def route-handler
  (fn [{:as req :keys [uri]}]
    (tap> req)
    (let [{:as match :keys [view query post handler params public]} (routes/match-path uri)
          params (cond->> params
                          (:query-params req)
                          (merge (update-keys (:query-params req) keyword)))
          method (:request-method req)
          data-req? (some-> (get-in req [:headers "accept"]) (str/includes? "application/transit+json"))
          authed? (:account req)]
      (cond

        (and (not authed?) (not public)) (throw (ex-info "Unauthorized" {:uri uri
                                                                         :match match
                                                                         :status 401}))

        handler (handler req params)

        ;; post fns are expected to return HTTP response maps
        (and post (= method :post)) (apply post req params (:body-params req))

        (and query data-req?) (some-> (query params) deref ring.http/ok)
        (or query view) (server.html/app-page
                          {:tx [(assoc env/client-config :db/id :env/config)
                                (assoc (:account req) :db/id :env/account)]})

        ;; query fns return reactions which must be wrapped in HTTP response maps
        :else (ring.http/not-found "Not found")))))


(memo/defn-memo $txs [ref]
  (r/catch
    (sync.entity/txs ref)
    (fn [e]
      (println "Error in $resolve-query")
      (println e)
      {:error (ex-message e)})))

(defn resolve-query [[_ params :as route]]
  (tap> [:resolve-query route])
  (let [{[_ matched-params] :route :keys [$query] :as match} (routes/match-path route)]
    (tap> [:match match])
    (when $query
      ($txs ($query (merge {} params matched-params))))))

(def ws-options {:handlers (merge (sync/query-handlers resolve-query)
                                  {::sync/once
                                   (fn [{:keys [channel]} qvec]
                                     (let [{:keys [query]} (routes/match-path qvec)]
                                       (sync/send channel
                                                  (sync/wrap-result qvec {:value (apply query (rest qvec))}))))})})

(defn ws-handler [req _]
  (#'ws/handle-ws-request ws-options req))

(def handler
  (impl/join-handlers (serve-static "public")
                      slack.server/handlers
                      (-> #'route-handler
                          accounts/wrap-accounts
                          impl/wrap-query-params            ;; required for accounts (oauth2)
                          wrap-log
                          ring.cookies/wrap-cookies
                          (muu.middleware/wrap-format muuntaja))))

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
  (when (not= "dev" (env/config :env))                      ;; using shadow-cljs server in dev
    (nrepl/start!)))

(defn -main []
  (log/info "Starting server" {:jvm (System/getProperty "java.vm.version")})
  (fire-jvm/sync-all)                                       ;; cache firebase db locally
  (restart-server! (or (some-> (System/getenv "PORT") (Integer/parseInt))
                       3000)))

(comment                                                    ;;; Webserver control panel
  (-main)

  (restart-server! 3000))