(ns org.sparkboard.client
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [applied-science.js-interop :as j]
            [clojure.edn :as edn]
            [org.sparkboard.client.auth :as auth.client]
            [org.sparkboard.client.firebase :as firebase]
            [org.sparkboard.client.routes :refer [routes]]
            [org.sparkboard.client.slack :as slack.client]
            [re-db.integrations.reagent] ;; extends `ratom` reactivity
            [re-db.reactive :as r]
            [re-db.sync :as sync]
            [re-db.transit]
            [re-db.xform :as xf]
            [reagent.core :as reagent]
            [reagent.dom]
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

(v/defview home [] "Nothing to see here, folks.")

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
        chan (atom {:!last-message (atom nil)})
        send (fn [message]
               (let [^js ws (:ws @chan)]
                 (when (= 1 (.-readyState ws))
                   (.send ws (pack message)))))
        _ (swap! chan assoc :send send)
        context (assoc options :channel chan)
        init-ws (fn init-ws []
                  (let [ws (js/WebSocket. (or url (str "ws://localhost:" port path)))]
                    (swap! chan assoc :ws ws)
                    (doto ws
                      (.addEventListener "open" (fn [_]
                                                  (sync/on-open chan)))
                      (.addEventListener "close" (fn [_]
                                                   (sync/on-close chan)
                                                   #_(js/setTimeout init-ws 1000)))
                      (.addEventListener "message" #(let [message (unpack (j/get % :data))]
                                                      (reset! (:!last-message @chan) message)
                                                      (sync/handle-message handlers context message))))))]
    (init-ws)
    chan))

(def channel
  (delay (connect {:port 3000
                   :handlers (sync/watch-handlers)})))

(v/defview playground []
  [:div
   [:a {:href "/skeleton"} "skeleton"]
   [:p (str "Current value: " (deref (sync/$watch @channel :sb/orgs #_:entity-1)))]
   [:button.p-2.rounded.bg-blue-100
    {:on-click #(sync/send @channel [:conj!])}
    "List, grow!"]])

(v/defview skeleton []
  [:div
   (into [:ul]
         (map (fn [org-obj] (vector :li [:a {:href (str "/skeleton/org/" (:org/id org-obj))} (:org/title org-obj)])))
         ;; FIXME double `value` nesting
         (:value (:value (edn/read-string (str (deref (sync/$watch @channel :sb/orgs)))))))])

;; BUG this somehow causes reactivity to work on page load. leaving it here to continue work on other pieces but this is 100% a HACK to be fixed & removed
(reagent.dom/render [skeleton]
                    (.-body js/document))



;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def handlers {:home home
               :playground playground
               :skeleton skeleton
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
