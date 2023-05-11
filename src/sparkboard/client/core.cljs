(ns sparkboard.client.core
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [applied-science.js-interop :as j]
            [re-db.api :as db]
            [re-db.integrations.reagent]
            [sparkboard.client.scratch]
            [sparkboard.client.views :as views]
            [sparkboard.routes :as routes]
            [sparkboard.slack.firebase :as firebase]
            [sparkboard.transit :as transit]                ;; extends `ratom` reactivity
            [sparkboard.views.ui :as ui]
            [vendor.pushy.core :as pushy]
            [yawn.root :as root]))

(ui/defview root []
  (let [{:keys [path view params tag route header?] :or {header? true}} (db/get :env/location)
        params (assoc params :path path :route route)]
    [:div.w-full.font-sans
     [:Suspense {:fallback "ROUGH spinner"}
      (when view
        [:<>
         (when header? [views/header params])
         [view params]])]]))

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
  (ui/init-forms)
  (render))

(comment
  (db/transact! [[:db/retractEntity :test]])
  (db/transact! [#_{:db/id :a :b 1}
                 {:db/id :test3 :a :b}
                 {:db/id :test2}])
  (:test2 (:eav @(db/conn)))
  )