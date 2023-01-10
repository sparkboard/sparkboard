(ns org.sparkboard.client
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [applied-science.js-interop :as j]
            [org.sparkboard.client.auth :as auth.client]
            [org.sparkboard.client.firebase :as firebase]
            [org.sparkboard.client.routes :refer [routes]]
            [org.sparkboard.client.slack :as slack.client]
            [re-db.sync :as sync]
            [re-db.transit]
            [reitit.core :as reitit]
            [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe]
            [triple.view :as v]
            [triple.view.react.experimental.atom :as ratom]))

(defonce !current-match (ratom/create nil))

(def router (rf/router routes {}))

(defn path [k & [params]]
  (-> router
      (reitit/match-by-name k params)
      (reitit/match->path)))


;;; XXX based on sync_values
(def default-options
  {:pack re-db.transit/pack
   :unpack re-db.transit/unpack
   :path "/ws"})

(defn connect
  "Connects to websocket server, returns a channel.

     :on-open [socket]
     :on-message [socket, message]
     :on-close [socket]
     "
  [& {:as options}]
  (let [{:keys [url port path pack unpack handlers]} (merge default-options options)
        channel (atom {:!last-message (atom nil)})
        send (fn [message]
               (let [^js ws (:ws @channel)]
                 (when (= 1 (.-readyState ws))
                   (.send ws (pack message)))))
        _ (swap! channel assoc :send send)
        context (assoc options :channel channel)
        init-ws (fn init-ws []
                  (let [ws (js/WebSocket. (or url (str "ws://localhost:" port path)))]
                    (swap! channel assoc :ws ws)
                    (doto ws
                      (.addEventListener "open" (fn [_]
                                                  (sync/on-open channel)))
                      (.addEventListener "close" (fn [_]
                                                   (sync/on-close channel)
                                                   #_(js/setTimeout init-ws 1000)))
                      (.addEventListener "message" #(let [message (unpack (j/get % :data))]
                                                      (reset! (:!last-message @channel) message)
                                                      (sync/handle-message handlers context message))))))]
    (init-ws)
    channel))

(def channel
  (delay (connect {:port 3000
                   :handlers (sync/watch-handlers)})))


(js/console.log "foo")


(v/defview home []
  [:button.p-2.rounded.bg-blue-100
   {:on-click #(sync/send @channel [:conj!])}
   "List, grow!"]
  #_"Nothing to see here, folks.")

(def handlers {:home home
               :slack/invite-offer slack.client/invite-offer
               :slack/link-complete slack.client/link-complete
               :auth-test auth.client/auth-header})

(v/defview root []
  (let [{:keys [view] :as m} @!current-match]
    (if view
      [view m]
      "not-found")))

(defn render []
  (react-dom/render (v/to-element [root]) (js/document.getElementById "web")))

(defn ^:dev/after-load start-router []
  (rfe/start!
    router
    (fn [m]
      (reset! !current-match (assoc m :view (handlers (:name (:data m))))))
    ;; set to false to enable HistoryAPI
    {:use-fragment false}))

(defn init []

  (firebase/init)

  (when (exists? js/ReactRefresh)
    (.injectIntoGlobalHook js/ReactRefresh goog/global))

  (start-router)
  (render))
