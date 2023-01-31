(ns org.sparkboard.client
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [org.sparkboard.client.auth]
            [org.sparkboard.client.firebase :as firebase]
            [org.sparkboard.client.views :as views]
            [org.sparkboard.routes :as routes]
            [pushy.core :as pushy]
            [re-db.integrations.reagent] ;; extends `ratom` reactivity
            [yawn.hooks :refer [use-deref]]
            [yawn.root :as root]
            [yawn.view :as v]))

(v/defview root [] ;; top level view wrapper
  (let [{:as current-location :keys [path view route-params query-params tag]} (use-deref routes/!current-location)]
    [:<>
     [views/global-header current-location]
     (if view
       [view (assoc route-params :path path :query-params query-params)]
       (str "No view found for " tag))
     [views/dev-drawer {:fixed? view} current-location]]))

(defonce !react-root (atom nil))

(defn render []
  (root/render @!react-root (root)))

(defn ^:dev/after-load start-router []
  (pushy/start! routes/history) )

(defn init []
  (firebase/init)

  (reset! !react-root (root/create :web (root)))

  (start-router)
  (render))
