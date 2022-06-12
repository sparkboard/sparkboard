(ns org.sparkboard.client
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [org.sparkboard.client.auth :as auth.client]
            [org.sparkboard.client.firebase :as firebase]
            [org.sparkboard.client.routes :refer [routes]]
            [org.sparkboard.client.slack :as slack.client]
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
