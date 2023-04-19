(ns sparkboard.client
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [applied-science.js-interop :as j]
            [re-db.api :as db]
            [sparkboard.client.auth]
            [sparkboard.client.firebase :as firebase]
            [sparkboard.client.views :as views]
            [sparkboard.routes :as routes]
            [sparkboard.transit :as transit]
            [vendor.pushy.core :as pushy]
            [re-db.integrations.reagent] ;; extends `ratom` reactivity
            [sparkboard.macros :refer [defview]]
            [yawn.hooks :refer [use-deref]]
            [yawn.root :as root]))

(defview root [] ;; top level view wrapper
  (let [{:as current-location :keys [path view params tag route]} (use-deref routes/!current-location)]
    [:<>
     [views/global-header current-location]
     [:Suspense {:fallback "ROUGH spinner"}
      (if view
        [view (assoc params :path path :route route)]
        (str "No view found for " tag))]
     [views/dev-drawer current-location]]))

(defonce !react-root (delay (root/create :web (root))))

(defn render []
  (root/render @!react-root (root)))

(defn ^:dev/after-load start-router []
  (pushy/start! routes/history))

(defn read-env! []
  (doseq [{:keys [tx schema]} (->> (js/document.querySelectorAll (str "[type='application/re-db']"))
                                   (map (comp transit/read (j/get :innerHTML))))]
    (some-> schema db/merge-schema!)
    (some-> tx db/transact!)))

(defn init []
  (read-env!)
  (firebase/init)
  (start-router)
  (render))
