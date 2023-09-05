(ns sparkboard.client.core
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [applied-science.js-interop :as j]
            [inside-out.forms :as forms]
            [re-db.api :as db]
            [re-db.integrations.reagent]
            [sparkboard.client.scratch]
            [sparkboard.entities.domain :as domain]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.views.radix :as radix]
            [sparkboard.routes :as routes]
            [sparkboard.slack.firebase :as firebase]
            [sparkboard.transit :as transit]                ;; extends `ratom` reactivity
            [sparkboard.views.ui :as ui]
            [vendor.pushy.core :as pushy]
            [yawn.root :as root]))

(ui/defview root
  []
  (let [{:as match :keys [modal]} (db/get :env/location)]
    [:div.w-full.font-sans
     [:Suspense {:fallback "ROUGH spinner"}

      (ui/show-match match)

      (radix/dialog {:props/root {:open           (boolean modal)
                                  :on-open-change #(when-not %
                                                     (routes/set-modal! nil))}}
                    (ui/show-match modal))]]))

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

(defn ^:dev/after-load init-forms []
  #_(when k
      (let [validator (some-> schema/sb-schema (get k) :malli/schema malli-validator)]
        (cond-> (k field-meta)
                validator
                (update :validators conj validator))))
  (forms/set-global-meta!
    {:account/email    {:el         ui/text-field
                        :props      {:type        "email"
                                     :placeholder (tr :tr/email)}
                        :validators [ui/email-validator]}
     :account/password {:el         ui/text-field
                        :props      {:type        "password"
                                     :placeholder (tr :tr/password)}
                        :validators [(forms/min-length 8)]}
     :entity/domain    {:validators [domain/domain-valid-string
                                     (domain/domain-availability-validator)]}})
  )

(defn init []
  (read-env!)
  (firebase/init)
  (start-router)
  (init-forms)
  (render))

(comment
  (db/transact! [[:db/retractEntity :test]])
  (db/transact! [#_{:db/id :a :b 1}
                 {:db/id :test3 :a :b}
                 {:db/id :test2}])
  (:test2 (:eav @(db/conn)))
  )