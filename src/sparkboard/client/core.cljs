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
            [sparkboard.schema :as sch]
            [sparkboard.ui.radix :as radix]
            [sparkboard.routes :as routes]
            [sparkboard.slack.firebase :as firebase]
            [sparkboard.transit :as transit]
            [sparkboard.ui :as ui]
            [yawn.root :as root]
            [sparkboard.app :as app]                        ;; includes all endpoints
            [yawn.view :as v]
            [re-db.in-memory :as mem]))

(ui/defview dev-info [{:as match :keys [modal]}]
  (into [:div.flex.justify-center.gap-2.fixed.left-0.bottom-0.border {:style {:z-index 9000}}]
        (for [info [(some-> match :match/endpoints :view :endpoint/tag str)
                    (when-let [modal-tag (some-> modal :view :endpoint/tag str)]
                      [:span [:span.font-bold "modal: "] modal-tag])]
              :when info]
          [:div.rounded.bg-gray-200.inline-block.px-2.py-1.text-sm.text-gray-600.relative
           info])))

#_{:fallback (loading:spinner "w-4 h-4 absolute top-2 right-2")}

(def default-loading-bar (v/x [ui/loading-bar "bg-blue-100 h-1"]))

(ui/defview root
  []
  (let [{:as match :keys [modal]} (react/useDeferredValue (db/get :env/location))]
    [:div.w-full.font-sans
     (dev-info match)
     [:Suspense {:fallback default-loading-bar}
      (ui/boundary {:on-error (fn [e]
                                (v/x [:div.p-6
                                      [ui/hero {:class "bg-red-100 border-red-400/50 border border-4"}
                                       (ex-message e)]]))}
        (ui/show-match match))]

     (radix/dialog {:props/root {:open           (boolean modal)
                                 :on-open-change #(when-not %
                                                    (routes/set-modal! nil))}}
                   [:Suspense {:fallback default-loading-bar}
                    (ui/show-match modal)])]))

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
    {:account/email      {:el         ui/text-field
                          :props      {:type        "email"
                                       :placeholder (tr :tr/email)}
                          :validators [ui/email-validator]}
     :account/password   {:el         ui/text-field
                          :props      {:type        "password"
                                       :placeholder (tr :tr/password)}
                          :validators [(forms/min-length 8)]}
     :entity/title       {:validators [(forms/min-length 3)]
                          :label      (tr :tr/title)}
     :entity/description {:label (tr :tr/description)}

     :entity/domain      {:label      (tr :tr/domain-name)
                          :validators [domain/domain-valid-string
                                       (domain/domain-availability-validator)]}})
  )

(defn ^:dev/after-load init-endpoints! []
  (routes/init-endpoints! app/client-endpoints))

(defn init []
  (db/merge-schema! @sch/!schema)
  (read-env!)
  (firebase/init)
  (init-endpoints!)
  (init-forms)
  (render))

(comment
  (routes/href ['sparkboard.app.board/new])
  @routes/!routes
  (db/transact! [[:db/retractEntity :test]])
  (db/transact! [#_{:db/id :a :b 1}
                 {:db/id :test3 :a :b}
                 {:db/id :test2}])
  (:test2 (:eav @(db/conn)))
  (db/transact!
    [{:member/roles        #{:role/admin}
      :member/last-visited #inst "2023-11-13T19:04:42.238-00:00"

      :entity/kind         :board
      :entity/title        "Huebert's Projects"
      :image/avatar        {:asset/id       #uuid "225f7a0a-9db1-4d0d-924a-17a110fe84dd"
                            :asset/provider {:s3/bucket-host "https://dev.r2.sparkboard.com"}}
      :db/id               [:entity/id #uuid "a1eebd1e-8b71-4925-bbfd-1b7f6a6b680e"]}])

  )

