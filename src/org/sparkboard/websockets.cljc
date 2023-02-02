(ns org.sparkboard.websockets
  (:require #?(:clj [org.httpkit.server :as httpkit])
            #?(:cljs [yawn.hooks :refer [use-deref]])
            [applied-science.js-interop :as j]
            [re-db.sync :as sync]
            [re-db.sync.transit :as transit]
            [org.sparkboard.routes :as routes]))

(def default-options
  "Websocket config fallbacks"
  {:pack transit/pack
   :unpack transit/unpack
   :path "/ws"})

#?(:clj
   (defn handle-ws-request [options
                            {:as request :keys [uri request-method]}]
     (let [{:as options :keys [path pack unpack handlers]} (merge default-options options)]
       (if (and (= :get request-method)
                (= path uri))
         (let [!ch (atom nil)
               channel {:!ch !ch
                        ::sync/send (fn [message]
                                      (if (some-> @!ch httpkit/open?)
                                        (httpkit/send! @!ch (pack message))
                                        (println :sending-message-before-open message)))}
               context {:channel channel}]
           (httpkit/as-channel request
                               {:init (partial reset! !ch)
                                :on-open sync/on-open
                                :on-receive (fn [ch message]
                                              (sync/handle-message handlers context (unpack message)))
                                :on-close (fn [ch status]
                                            (sync/on-close channel))}))
         (throw (ex-info (str "Unknown request " request-method uri) request))))))

#?(:clj
   (defn handler [path options]
     (fn [req]
       (when (= path (:uri req))
         (handle-ws-request options req)))))

#?(:cljs
   (defn connect
     "Connects to websocket server, returns a channel.

     :on-open [socket]
     :on-message [socket, message]
     :on-close [socket]
     "
     [& {:as options}]
     (let [{:keys [url port path pack unpack handlers]} (merge default-options options)
           !ws (atom nil)
           channel {:!last-message (atom nil)
                    :ws !ws
                    ::sync/send (fn [message]
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
   (def channel
     (delay (connect {:port 3000
                      :handlers (sync/result-handlers)}))))

#?(:cljs
   (defn $query [query-vec]
     (sync/$query @channel query-vec)))

#?(:cljs
   (defn use-query! [query-vec]
     (let [{:as x :keys [value error loading?]} (use-deref ($query (routes/path->route query-vec)))]
       (cond error (throw (ex-info error {:query query-vec}))
             loading? (throw loading?)
             :else value))))

#?(:cljs
   (def use-result (comp use-deref $query)))

#?(:cljs
   (defn send [message]
     (sync/send @channel message)))