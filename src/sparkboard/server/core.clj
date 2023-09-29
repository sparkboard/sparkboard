(ns sparkboard.server.core
  "HTTP server handling all requests
   * slack integration
   * synced queries over websocket"
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
            [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.read :as read]
            [re-db.sync :as sync]
            [re-db.sync.entity-diff-1 :as sync.entity]
            [ring.middleware.basic-authentication :as basic-auth]
            [ring.middleware.cookies :as ring.cookies]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.middleware.params :as ring.params]
            [ring.util.http-response :as ring.http]
            [ring.util.mime-type :as ring.mime]
            [ring.middleware.multipart-params :as multipart]
            [ring.util.request]
            [ring.util.response :as ring.response]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.i18n :as i18n]
            [sparkboard.log]
            [sparkboard.routes :as routes]
            [sparkboard.app]                                ;; includes all endpoints
            [sparkboard.server.accounts :as accounts]
            [sparkboard.server.env :as env]
            [sparkboard.server.html :as server.html]
            [sparkboard.server.nrepl :as nrepl]
            [sparkboard.slack.firebase.jvm :as fire-jvm]
            [sparkboard.slack.server :as slack.server]
            [sparkboard.transit :as t]
            [sparkboard.websockets :as ws]
            [sparkboard.util :as u]
            [taoensso.timbre :as log]
            [shadow.resource]))

(defn req-auth-token [req]
  (or (some-> (:headers req) (get "authorization") (str/replace #"^Bearer: " ""))
      (-> req :params :token)))

(defn join-handlers
  "Join a sequence of handlers into a single handler, returning the first non-nil response."
  [& handlers]
  (fn [req]
    (some (fn [handler] (handler req)) handlers)))

(defn wrap-query-params [f]
  (fn [req]
    (f (ring.params/assoc-query-params
         req
         (or (ring.util.request/character-encoding req) "UTF-8")))))

(def muuntaja
  ;; Note: the `:body` BytesInputStream will be present but decode/`slurp` to an
  ;; empty String if no read format is declared for the request's content-type.
  (muu/create m/default-options))

(defn data-req? [req]
  (some->> (get-in req [:headers "accept"])
           (re-find #"^application/(?:transit\+json|json)")))

(defn wrap-log
  "Log requests (log/info) and errors (log/error)"
  [f]
  (let [handle-e (fn [req e]
                   (tap> e)
                   (log/error (ex-message e)
                              (ex-data e)
                              (ex-cause e))
                   (let [{:as   data
                          :keys [wrap-response]
                          :or   {wrap-response identity}} (ex-data e)]
                     (wrap-response
                       (if (data-req? req)
                         (or (:response data)
                             {:status (:code data 500)
                              :body   {:error (or (:message data)
                                                  (ex-message e))}})
                         (server.html/error e data)))))]
    (fn [req]
      #_(log/info :req req)
      (try (let [res (f req)]
             #_(log/info :res res)
             res)
           (catch Exception e (handle-e req e))
           (catch java.lang.AssertionError e (handle-e req e))))))

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
  (read/transact! dl/conn txs))

#_(memo/clear-memo! $resolve-ref)

(defn document
  {:endpoint         {:get ["/documents/" :file/name]}
   :endpoint/tag     'document
   :endpoint/public? true}
  [_ {:keys [file/name]}]
  (server.html/formatted-text (md/md-to-html-string (slurp (io/resource (str "sparkboard/documents/" name ".md"))))))

(defn authorize! [f req params]
  (when
    (and (not (:endpoint/public? (meta f)))
         (not (:account req)))
    (throw (ex-info "Unauthorized" {:uri      [(:request-method req) (:uri req)]
                                    :endpoint (meta f)
                                    :status   401})))

  (if-let [authorize (:authorize (meta f))]
    (authorize req params)
    params))

(memo/defn-memo $txs [ref]
  (r/catch
    (sync.entity/txs ref)
    (fn [e]
      (println "Error in $resolve-query")
      (println e)
      {:error (ex-message e)})))

(def make-$query
  (memoize
    (fn [fn-var]
      (u/memo-fn-var fn-var))))

(defn resolve-query [[id params :as qvec]]
  (let [endpoint  (routes/by-tag id :query)
        _         (assert endpoint (str "resolve: " id " is not a query endpoint"))
        query-var (-> endpoint :endpoint/sym requiring-resolve)
        context   (meta qvec)
        params    (merge {}
                         (cond->> params
                                  (::sync/watch context)
                                  (authorize! query-var context))
                         params)
        $query    (make-$query query-var)]
    ($txs ($query params))))

(comment
  (resolve-query ['sparkboard.app.org/db:read {}])
  (routes/by-tag 'sparkboard.app.org/db:read :query)
  @routes/!routes)

(def ws-options {:handlers (merge (sync/query-handlers resolve-query)
                                  {::sync/once
                                   ;; TODO what is this and how do I handle params...
                                   (fn [{:keys [channel]} qvec]
                                     (let [match    (routes/match-route qvec)
                                           query-fn (-> match :match/endpoints :query :endpoint/sym requiring-resolve)]
                                       (sync/send channel
                                                  (sync/wrap-result qvec {:value (apply query-fn (rest qvec))}))))})})

(defn websocket
  {:endpoint         {:get ["/ws"]}
   :endpoint/public? true}
  [req _]
  (#'ws/handle-ws-request ws-options req))

(defn spa-handler [req _params]
  (server.html/app-page
    {:tx [(assoc env/client-config :db/id :env/config)
          (assoc (:account req) :db/id :env/account)]}))

(defn pull
  {:endpoint {:query ["/pull"]}}
  ;; TODO
  ;; generic, rule-based auth?
  [{:keys [id expr]}]
  (db/pull expr id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Routes

(def route-handler
  (fn [{:as req :keys [uri request-method]}]
    (let [{:as         match
           :match/keys [endpoints params]} (routes/match-route uri)]
      (if-let [{:keys [endpoint/sym]} (get endpoints request-method)]
        (let [handler (requiring-resolve sym)
              params  (-> params
                          (u/assoc-seq :query-params (update-keys (:query-params req) keyword))
                          (u/assoc-some :body (:body-params req (:body req))))]
          (handler req (authorize! handler req params)))
        (if (:view endpoints)
          (server.html/app-page
            {:tx [(assoc env/client-config :db/id :env/config)
                  (assoc (:account req) :db/id :env/account)]})
          (ring.http/not-found "Not found"))))))

(def app-handler
  (delay
    (join-handlers (serve-static "public")
                   slack.server/handlers
                   (-> #'route-handler
                       i18n/wrap-i18n
                       accounts/wrap-accounts
                       wrap-query-params               ;; required for accounts (oauth2)
                       multipart/wrap-multipart-params
                       wrap-log
                       ring.cookies/wrap-cookies
                       (muu.middleware/wrap-format muuntaja)
                       (cond->
                         (= "staging" (env/config :env))
                         (basic-auth/wrap-basic-authentication (fn [_user pass]
                                                                 (when (= pass (env/config :basic-auth/password))
                                                                   "admin"))))))))

(defonce the-server
         (atom nil))

(defn stop-server! []
  (some-> @the-server (httpkit/server-stop!))
  (nrepl/stop!))

(defn restart-server!
  "Setup fn.
  Starts HTTP server, stopping existing HTTP server first if necessary."
  [port]
  (routes/init-endpoints!
    (concat (routes/clj-endpoints)
            (t/read (slurp (io/resource "public/js/sparkboard-views.transit.json")))))
  (stop-server!)
  (reset! the-server (httpkit/run-server (fn [req] (@app-handler req))
                                         {:port                 port
                                          :legacy-return-value? false}))
  (when (not= "dev" (env/config :env))                      ;; using shadow-cljs server in dev
    (nrepl/start!)))

(defn -main [& [port]]
  (log/info "Starting server" {:jvm (System/getProperty "java.vm.version")})
  (fire-jvm/sync-all)                                       ;; cache firebase db locally
  (restart-server! (or (some-> (System/getenv "PORT") (Integer/parseInt))
                       port
                       3000)))

(comment                                                    ;;; Webserver control panel
  (-main)

  @routes/!routes
  (routes/match-route (str "/o/" (random-uuid)))

  (routes/path-for 'sparkboard.app.account/db:read)
  (routes/match-route (str "/o/" (random-uuid)))
  (routes/match-route (str "/ws"))


  @routes/!tags
  (routes/by-tag 'sparkboard.app.org/db:read
                 :query)
  (restart-server! 3000)
  )