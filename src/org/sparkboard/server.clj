(ns org.sparkboard.server
  "HTTP server handling all requests
   * slack integration
   * synced queries over websocket"
  (:gen-class)
  (:require [bidi.ring :as bidi.ring]
            [hiccup.util]
            [mhuebert.cljs-static.html :as html]
            [org.httpkit.server :as httpkit]
            [org.sparkboard.datalevin :as datalevin]
            [org.sparkboard.firebase.jvm :as fire-jvm]
            [org.sparkboard.firebase.tokens :as fire-tokens]
            [org.sparkboard.log]
            [org.sparkboard.server.env :as env]
            [org.sparkboard.server.impl :as impl]
            [org.sparkboard.server.nrepl :as nrepl]
            [org.sparkboard.slack.server :as slack.server]
            [re-db.api]
            [re-db.memo :as memo]
            [re-db.query]
            [re-db.read :as read]
            [re-db.sync :as sync]
            [re-db.xform :as xf]
            [ring.middleware.defaults]
            [ring.middleware.format]
            [ring.util.mime-type :as ring.mime-type]
            [ring.util.request]
            [ring.util.response :as ring.response]
            [taoensso.timbre :as log]
            [tools.sparkboard.transit :as transit]
            [org.sparkboard.websockets :as ws])
  (:import [java.time Instant]))

(comment
 (fire-tokens/decode token))

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

(defn wrap-iff
  "Applies the given middleware `mw` only on the condition that the
  request satisfies predicate `pred`."
  [f pred mw]
  (fn [req]
    (if (pred req)
      (mw (f req))
      (f req))))

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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Websockets

;; DEBUG
(defonce !list (atom ()))

(memo/defn-memo $values [ref]
  (xf/transform ref
    (map (fn [v] {:value v}))))



;; wrap a `transact!` function to call `read/handle-report!` afterwards, which will
;; cause dependent queries to re-evaluate.
(defn transact! [txs]
  (->> (re-db.api/transact! datalevin/conn txs)
       (read/handle-report! datalevin/conn)))

;; an atom of refs to expose, a map of ids to functions which return reactions.
;; refs are requested via vectors of the form [<id> & args].
(def !refs
  (atom {:sb/orgs (constantly (re-db.query/reaction datalevin/conn
                                                    (mapv (re-db.api/pull '[*])
                                                          (datalevin/qry-orgs))))
         :entity-1 (constantly (re-db.query/reaction datalevin/conn
                                                     (re-db.api/pull '[*] [:org/id "opengeneva"])
                                                     ;; Q: why does `get` work but the result of `some` doesn't? it doesn't seem to be based on Entity type
                                                     #_(:db/id (some #(when (-> % :org/id #{"opengeneva"})
                                                                        %)
                                                                     (datalevin/qry-orgs)))))
         :entity (fn [id] (re-db.query/reaction datalevin/conn
                                                (re-db.api/get id)))}))

(def app
  (-> (bidi.ring/make-handler ["/" (merge slack.server/routes
                                          {"ws" (partial ws/handle-ws-request
                                                  {:handlers
                                                   (merge
                                                    {:conj! (fn [_] (swap! !list conj (rand-int 100)))}
                                                    (sync/query-handlers
                                                     (fn [query-vec]
                                                       (let [[id & args] (if (sequential? query-vec)
                                                                           query-vec
                                                                           [query-vec])]
                                                         (apply (@!refs id) args)))))})})])
      (ring.middleware.defaults/wrap-defaults ring.middleware.defaults/api-defaults)
      (wrap-iff (complement :websocket?) ;; only apply RESTful middleware on
                ;; non-websocket requests
                (ring.middleware.format/wrap-restful-format {:formats [:json-kw
                                                                       :transit-json]}))
      slack.server/wrap-slack-verify
      wrap-static-fallback
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
