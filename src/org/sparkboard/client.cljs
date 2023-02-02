(ns org.sparkboard.client
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [org.sparkboard.client.auth]
            [org.sparkboard.client.firebase :as firebase]
            [org.sparkboard.client.views :as views]
            [org.sparkboard.routes :as routes]
            [org.sparkboard.views.rough :as rough]
            [vendor.pushy.core :as pushy]
            [re-db.integrations.reagent] ;; extends `ratom` reactivity
            [yawn.hooks :refer [use-deref]]
            [yawn.root :as root]
            [yawn.view :as v]))

(v/defview root [] ;; top level view wrapper
  (let [{:as current-location :keys [path view params tag route]} (use-deref routes/!current-route)]
    [:<>
     [views/global-header current-location]
     [:Suspense {:fallback (v/x [rough/spinner])}
      (if view
        [view (assoc params :path path :route route)]
        (str "No view found for " tag))]
     [views/dev-drawer current-location]]))

(defonce !react-root (delay (root/create :web (root))))

(defn render []
  (root/render @!react-root (root)))

(defn ^:dev/after-load start-router []
  (pushy/start! routes/history))

(defn init []
  (firebase/init)
  (start-router)
  (render))
