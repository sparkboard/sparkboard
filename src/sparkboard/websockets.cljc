(ns sparkboard.websockets
  (:require #?(:clj [org.httpkit.server :as httpkit])
            #?(:cljs [yawn.hooks :as h :refer [use-deref]])
            [applied-science.js-interop :as j]
            [re-db.sync :as sync]
            [re-db.sync.transit :as transit]
            [sparkboard.routes :as routes]))

(def default-options
  "Websocket config fallbacks"
  {:pack transit/pack
   :unpack transit/unpack
   :path "/ws"})

#?(:clj
   (defn handle-ws-request [options req]
     (let [{:as options :keys [pack unpack handlers]} (merge default-options options)]
       (if (= :get (:request-method req))
         (let [!ch (atom nil)
               channel {:!ch !ch
                        ::sync/send (fn [message]
                                      (if (some-> @!ch httpkit/open?)
                                        (httpkit/send! @!ch (pack message))
                                        (println :sending-message-before-open message)))}
               context {:channel channel
                        :account (:account req)}]
           (httpkit/as-channel req
                               {:init (partial reset! !ch)
                                :on-open sync/on-open
                                :on-receive (fn [ch message]
                                              (sync/handle-message handlers context (unpack message)))
                                :on-close (fn [ch status]
                                            (sync/on-close channel))}))
         {:status 400}))))

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
   (defn watch [query-vec]
     (let [query-vec (if (keyword? query-vec)
                       [query-vec {}]
                       query-vec)]
       (if (:query (routes/match-path query-vec))
         @(sync/$query @channel query-vec)
         {:error "Query not found"}))))

#?(:cljs
   (defn once [qvec]
     (if (:query (routes/match-path qvec))
       (or (sync/read-result qvec)
           (do (sync/start-loading! qvec)
               (sync/send @channel [::sync/once qvec])
               (sync/read-result qvec)))
       (delay {:error "Query not found"}))))

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
   (def use-query (comp use-cached-result watch)))

#?(:cljs
   (defn send [message]
     (sync/send @channel message)))