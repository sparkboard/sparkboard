(ns sparkboard.client.core
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [applied-science.js-interop :as j]
            [re-db.api :as db]
            [sparkboard.slack.firebase :as firebase]
            [sparkboard.client.views :as views]
            [sparkboard.routes :as routes]
            [sparkboard.transit :as transit]
            [vendor.pushy.core :as pushy]
            [re-db.integrations.reagent] ;; extends `ratom` reactivity
            [sparkboard.views.ui :as ui]
            [yawn.root :as root]
            [sparkboard.client.scratch]))

(ui/defview root []
  (let [{:as current-location :keys [path view params tag route]} (db/get :env/location)]
    [:div.w-full.font-sans
     [:Suspense {:fallback "ROUGH spinner"}
      (when view
        [view (assoc params :path path :route route)])]]))

(defonce !react-root (delay (root/create :web (root))))

(defn render []
  (root/render @!react-root (root)))

(defn ^:dev/after-load start-router []
  (pushy/start! routes/history))

(defn read-env! []
  (doseq [{:keys [tx schema]} (->> (js/document.querySelectorAll (str "[type='application/re-db']"))
                                   (map (comp transit/read (j/get :innerHTML))))]
    (some-> schema db/merge-schema!)
    (some-> tx db/transact! :db/after println)))

(defn init []
  (read-env!)
  (firebase/init)
  (start-router)
  (render))

(comment
 (db/transact! [[:db/retractEntity :test]])
 (db/transact! [#_{:db/id :a :b 1}
                {:db/id :test3 :a :b}
                {:db/id :test2}])
 (:test2 (:eav @(db/conn)))
 )