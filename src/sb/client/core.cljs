(ns sb.client.core
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [applied-science.js-interop :as j]
            [inside-out.forms :as forms]
            [org.sparkboard.slack.firebase :as firebase]
            [re-db.api :as db]
            [re-db.integrations.reagent]
            [sb.app :as app]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.client.scratch]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [sb.transit :as transit]
            [yawn.root :as root]
            [yawn.view :as v]))

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
  (let [{:as match :keys [router/root
                          router/modal]} (react/useDeferredValue @routing/!location)]
    [:div.w-full.font-sans
     (dev-info match)
     [:Suspense {:fallback default-loading-bar}
      (ui/boundary {:on-error (fn [e]
                                (v/x [:div.p-6
                                      [ui/hero {:class "bg-red-100 border-red-400/50 border border-4"}
                                       (ex-message e)]]))}
                   (ui/show-match root))]

     (radix/dialog {:props/root {:open           (boolean modal)
                                 :on-open-change #(when-not % (routing/dissoc-router! :router/modal))}}
                   [:Suspense {:fallback default-loading-bar}
                    (ui/show-match modal)])
     (radix/alert)]))

(defonce !react-root (delay (root/create :app-root (root))))

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
  (forms/set-global-meta! (fn [k]
                            (when k
                              (merge {:label (sb.i18n/tr* (keyword "tr" (name k)))}
                                     (app/global-field-meta k)))))
  )

(defn ^:dev/after-load init-endpoints! []
  (routing/init-endpoints! app/client-endpoints))

(defn init []
  (db/merge-schema! @sch/!schema)
  (read-env!)
  (firebase/init)
  (init-endpoints!)
  (init-forms)
  (render))

