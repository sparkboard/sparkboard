(ns sparkboard.client.core
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [applied-science.js-interop :as j]
            [inside-out.forms :as forms]
            [re-db.api :as db]
            [re-db.integrations.reagent]
            [sparkboard.client.scratch]
            [sparkboard.app.domain :as domain]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.ui.radix :as radix]
            [sparkboard.routes :as routes]
            [sparkboard.slack.firebase :as firebase]
            [sparkboard.transit :as transit]
            [sparkboard.ui :as ui]
            [yawn.root :as root]
            [sparkboard.app :as app]                        ;; includes all endpoints
            ))

(ui/defview dev-info [{:as match :keys [modal]}]
  (into [:div.p-2.flex.gap-2.fixed.top-0.right-0 {:style {:z-index 9000}}]
        (for [info [(some-> match :match/endpoints :view :endpoint/tag str)
                    (when-let [modal-tag (some-> modal :view :endpoint/tag str)]
                      [:span [:span.font-bold "modal: "] modal-tag])]
              :when info]
          [:div.rounded.bg-gray-100.inline-block.px-2.py-1.text-sm.text-gray-600.relative
           info])))

(ui/defview root
  []
  (let [{:as match :keys [modal]} (react/useDeferredValue (db/get :env/location))]
    [:div.w-full.font-sansa
     #_(dev-info match)
     (ui/try
       [:<>
        (ui/show-match match)

        (radix/dialog {:props/root {:open           (boolean modal)
                                    :on-open-change #(when-not %
                                                       (routes/set-modal! nil))}}
                      (ui/show-match modal))]
       (catch js/Error e
              (str "Error: " (ex-message e))))]))

(defonce !react-root (delay (root/create :web (root))))

(defn render []
  (root/render @!react-root (root)))

(defn read-env! []
  (doseq [{:keys [tx schema]} (->> (js/document.querySelectorAll (str "[type='application/re-db']"))
                                   (map (comp transit/read (j/get :innerHTML))))]
    (some-> schema db/merge-schema!)
    (some-> tx db/transact! :db/after println)))

(defn ^:dev/after-load init-forms []
  #_(when k
      (let [validator (some-> @schema/!schema (get k) :malli/schema malli-validator)]
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

(defn ^:dev/after-load init-endpoints! []
  (routes/init-endpoints! app/client-endpoints))

(defn init []
  (read-env!)
  (firebase/init)
  (init-endpoints!)
  (init-forms)
  (render))

(comment
  @routes/!routes
  (db/transact! [[:db/retractEntity :test]])
  (db/transact! [#_{:db/id :a :b 1}
                 {:db/id :test3 :a :b}
                 {:db/id :test2}])
  (:test2 (:eav @(db/conn)))
  )

