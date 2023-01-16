(ns org.sparkboard.client
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [org.sparkboard.client.auth]
            [org.sparkboard.client.firebase :as firebase]
            [org.sparkboard.client.views]
            [org.sparkboard.routes :as routes]
            [pushy.core :as pushy]
            [re-db.integrations.reagent] ;; extends `ratom` reactivity
            [shadow.lazy :as lazy]
            [yawn.hooks :as hooks]
            [yawn.root :as root]
            [yawn.view :as v]))

(defonce !current-location (atom nil))

(v/defview root []
  (let [{:as current-location :keys [path handler route-params]} (hooks/use-atom !current-location)]
    (if handler
      [handler (assoc route-params :path path)]
      "not-found")))

(defonce !react-root (atom nil))

(defn render []
  (root/render @!react-root (root)))

(defonce history (pushy/pushy
                  (fn [{:as match handler :handler}]
                    (if (instance? lazy/Loadable handler)
                      (lazy/load handler
                                 (fn [handler]
                                   (reset! !current-location (assoc match :handler handler))))
                      (reset! !current-location match)))
                  (fn [path]
                    (routes/match-route path))))

(defn ^:dev/after-load start-router []
  (pushy/start! history) )

(defn init []
  (firebase/init)

  (reset! !react-root (root/create :web (root)))

  (start-router)
  (render))
