(ns sparkboard.query
  (:refer-clojure :exclude [use ..])
  (:require #?(:clj [org.httpkit.server :as httpkit])
            [yawn.hooks :as h]
            [applied-science.js-interop :as j]
            [promesa.core :as p]
            [re-db.api :as db]
            [re-db.sync :as sync]
            [re-db.sync.transit :as transit]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [re-db.sync.entity-diff-2 :as entity-diff])
  #?(:cljs (:require-macros [sparkboard.query :as q])))

(def ws:default-options
  "Websocket config fallbacks"
  {:pack   transit/pack
   :unpack transit/unpack
   :path   "/ws"})

#?(:clj
   (defn ws:handle-request [options req]
     (let [{:as options :keys [pack unpack handlers]} (merge ws:default-options options)]
       (if (= :get (:request-method req))
         (let [!ch     (atom nil)
               channel {:!ch        !ch
                        ::sync/send (fn [message]
                                      (if (some-> @!ch httpkit/open?)
                                        (httpkit/send! @!ch (pack message))
                                        (println :sending-message-before-open message)))}
               context {:channel channel
                        :account (:account req)}]
           (httpkit/as-channel req
                               {:init       (partial reset! !ch)
                                :on-open    sync/on-open
                                :on-receive (fn [ch message]
                                              (sync/handle-message handlers context (unpack message)))
                                :on-close   (fn [ch status]
                                              (sync/on-close channel))}))
         {:status 400}))))

#?(:cljs
   (defn ws:connect
     "Connects to websocket server, returns a channel.

     :on-open [socket]
     :on-message [socket, message]
     :on-close [socket]
     "
     [& {:as options}]
     (let [{:keys [url port path pack unpack handlers]} (merge ws:default-options options)
           !ws     (atom nil)
           channel {:!last-message (atom nil)
                    :dispose-delay (* 10 60000)
                    :ws            !ws
                    ::sync/send    (fn [message]
                                     (let [^js ws @!ws]
                                       (when (= 1 (.-readyState ws))
                                         (.send ws (pack message)))))}
           context (assoc options :channel channel)
           init-ws (fn init-ws []
                     (let [ws (js/WebSocket. (or url (str "ws://localhost:" port path)))]
                       (reset! !ws ws)
                       (doto ws
                         (.addEventListener "open" (fn [_]
                                                     (sync/on-open channel)))
                         (.addEventListener "close" (fn [_]
                                                      (sync/on-close channel)
                                                      (js/setTimeout init-ws 1000)))
                         (.addEventListener "message" #(let [message (unpack (j/get % :data))]
                                                         (reset! (:!last-message channel) message)
                                                         (sync/handle-message handlers context message))))))]
       (init-ws)
       channel)))

#?(:cljs
   (defonce ws:channel
            (delay (ws:connect {:port     3000
                                :handlers (sync/result-handlers entity-diff/result-handlers)}))))

#?(:clj
   (def pull
     (db/partial-pull
       ;; at root, add :db/id [:ductile/id X]
       {:wrap-ref
        (fn [conn e]
          (if-let [entity-id (re-db.read/get conn e :entity/id)]
            [:entity/id entity-id]
            (do
              (prn ::pull (str "Warning: no entity/id found for ref " (db/touch (db/entity e))))
              e)))})))

(defn normalize-vec [[id params]] [id (or params {})])

#?(:cljs
   (defn subscribe [qvec]
     @(sync/$query @ws:channel (normalize-vec qvec))))

(comment
  (routes/by-tag 'sparkboard.app.org/db:read :query)
  @routes/!tags)

#?(:cljs
   (defn use-cached-result [{:as result :keys [loading? value]}]
     (let [!last-value (h/use-state value)]
       (h/use-effect
         (fn []
           (when (and (not loading?)
                      (not= value @!last-value))
             (reset! !last-value value)))
         [value loading?])
       (assoc result :value @!last-value))))

#?(:cljs
   (def use (comp use-cached-result subscribe)))

#?(:cljs
   (defn use! [qvec]
     (let [{:keys [value error loading?]} (use qvec)]
       (cond error (throw (ex-info error {:query qvec}))
             loading? (throw loading?)
             :else value))))

#?(:cljs
   (defn deref! [qvec]
     (let [{:keys [value error loading?]} @(sync/$query @ws:channel (normalize-vec qvec))]
       (cond error (throw (ex-info error {:query qvec}))
             loading? (throw loading?)
             :else value))))

(comment
  #?(:cljs
     (defn use-some [qvec]
       (let [{:keys [value error loading?]} (use qvec)]
         (cond error (throw (ex-info error {:query qvec}))
               loading? value
               :else value)))))

#?(:cljs
   (defn effect! [f & args]
     #_(throw (ex-info "Effect! error" {}))
     (-> (routes/POST 'sparkboard.server.core/effect! (into [f] args))
         (p/catch (fn [e] {:error (ex-message e)})))))

#?(:clj
   (defn op-impl [env op name args]
     (let [[name doc params argv body] (u/parse-defn-args name args)
           fqn (symbol (str *ns*) (str name))]
       (if (:ns env)
         (case op :query `(defn ~name [params#] (~'sparkboard.query/deref! ['~fqn params#]))
                  :effect `(defn ~name [& args#] (apply ~'sparkboard.query/effect! '~fqn args#)))
         `(defn ~name
            ~@(when doc [doc])
            ~(assoc-in params [:endpoint op] true)
            ~argv
            ~@body)))))
#?(:clj
   (defmacro defquery [name & args]
     (op-impl &env :query name args)))
#?(:clj
   (defmacro defx [name & args]
     (op-impl &env :effect name args)))
#?(:clj
   (defmacro server [& body]
     (when-not (:ns &env)
       `(do ~@body))))

(def from-var
  (memoize
    (fn [fn-var]
      (u/memo-fn-var fn-var))))

#?(:clj
   (defmacro $ [name & args]
     ;; invoke a fn as a query
     ;; (use this when consuming nested queries)
     `((from-var ~(resolve name)) ~@args)))

(comment
  (re-db.read/-resolve-e
    re-db.api/*conn*
    @re-db.api/*conn*
    [:entity/id #uuid "adc4e6a6-a97e-330b-8e0a-94268505bf37"]))

