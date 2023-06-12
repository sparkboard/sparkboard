(ns sparkboard.entities.account
  (:require ["@radix-ui/react-dropdown-menu" :as dm]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [sparkboard.icons :as icons]
            [sparkboard.views.radix :as radix]
            [re-db.api :as db]
            [sparkboard.entities.entity :as entity]
            [sparkboard.i18n :as i :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.views.ui :as ui]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(defn lang-menu-content-2 []
  (let [current-locale (i/current-locale)
        on-select      (fn [v]
                         (p/do (routes/POST :account/set-locale v)
                               (js/window.location.reload)))]
    (map (fn [lang]
           (let [selected (= lang current-locale)]
             [{:selected selected
               :on-click (when-not selected #(on-select lang))}
              (get-in i/dict [lang :meta/lect])]))
         (keys i/dict))))

(ui/defview header:lang [classes]
  [:div.inline-flex.flex-row.items-center {:class ["hover:text-txt-faded"
                                                   classes]}
   (apply radix/dropdown-menu
          {:trigger [icons/languages "w-5 h-5"]}
          (lang-menu-content-2))])

(defn icon:plus [& [classes]]
  (v/x
    [:svg {:xmlns   "http://www.w3.org/2000/svg"
           :viewBox "0 0 20 20"
           :fill    "currentColor"
           :class   classes}
     [:path
      {:d
       "M10.75 4.75a.75.75 0 00-1.5 0v4.5h-4.5a.75.75 0 000 1.5h4.5v4.5a.75.75 0 001.5 0v-4.5h4.5a.75.75 0 000-1.5h-4.5v-4.5z"}]]))

(defn icon:ellipsis-h [& [classes]]
  (v/x
    [:svg {:xmlns   "http://www.w3.org/2000/svg"
           :viewBox "0 0 20 20"
           :fill    "currentColor"
           :class   classes}
     [:path
      {:d
       "M3 10a1.5 1.5 0 113 0 1.5 1.5 0 01-3 0zM8.5 10a1.5 1.5 0 113 0 1.5 1.5 0 01-3 0zM15.5 8.5a1.5 1.5 0 100 3 1.5 1.5 0 000-3z"}]]))

(ui/defview header:account []
  (if-let [account (db/get :env/account)]
    (radix/dropdown-menu
      {:trigger [:div.flex.items-center [:img.rounded-full.h-8.w-8 {:src (ui/asset-src (:account/photo account) :logo)}]]}
      [{:on-click #(routes/set-path! :home)} (tr :tr/home)]
      [{:on-click #(routes/set-path! :account/logout)} (tr :tr/logout)]
      (into [{:sub?    true
              :trigger [icons/languages "w-5 h-5"]}] (lang-menu-content-2)))
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routes/path-for :account/sign-in)} (tr :tr/sign-in)]))

(defn header [params & children]
  [:div.entity-header
   [:a.text-lg.font-semibold.leading-6.flex.flex-grow.items-center {:href (routes/path-for :account/read params)} (tr :tr/my-stuff)]
   children
   [header:account]])

(ui/defview new-menu [params]
  (radix/dropdown-menu {:trigger [:div.btn.btn-primary (tr :tr/new) "..."]}
                       [{:on-click #(routes/set-path! :account/new-board params)} (tr :tr/board)]
                       [{:on-click #(routes/set-path! :account/new-org params)} (tr :tr/org)]))

(ui/defview read-view [{:as params :keys [data]}]
  (let [!tab (h/use-state (tr :tr/recent))]
    (ui/with-form [?pattern (when ?filter (str "(?i)" ?filter))]
      (let [show-results (fn [title results]
                           (when-let [results (seq (sequence (ui/filtered ?pattern) results))]
                             [:div.mt-6 {:key title}
                              (when title [:div.px-body.font-medium title [:hr.mt-2.sm:hidden]])
                              (into [:div.card-grid]
                                    (map entity/card)
                                    results)]))
            tab          (if @?pattern (tr :tr/search) @!tab)
            select-tab!  #(do (reset! ?filter nil)
                              (reset! !tab %))]
        [:<>
         (header params)

         ;; tabs
         [:div.mt-6.flex.items-stretch.px-body.gap-2
          (->> [(tr :tr/recent)
                (tr :tr/all)]
               (map (fn [k]
                      [:div.flex.items-center
                       {:on-click #(select-tab! k)
                        :class    (when-not (= k tab) "text-txt/50 hover:underline cursor-pointer")} k]))
               (into [:<>]))
          [:div.flex-grow]
          [ui/filter-field ?filter]
          [new-menu params]]

         (if (= tab (tr :tr/recent))
           (show-results nil (:recents data))
           [:<>
            (show-results (tr :tr/orgs) (:org data))
            (show-results (tr :tr/boards) (:board data))
            (show-results (tr :tr/projects) (:project data))])]))))


(defn account:sign-in-with-google []
  (ui/x
    [:a.btn.btn-light
     {:class "w-full h-10 text-zinc-500 text-sm"
      :href  (routes/path-for :oauth2.google/launch)}
     [:img.w-5.h-5.m-2 {:src "/images/google.svg"}] (tr :tr/sign-in-with-google)]))

(defn account:sign-in-terms []
  (ui/x
    [:p.px-8.text-center.text-sm {:class "text-txt/70"} (tr :tr/sign-in-agree-to)
     [:a.gray-link {:href "/documents/terms-of-service"} (tr :tr/tos)] ","
     [:a.gray-link {:target "_blank"
                    :href   "https://www.iubenda.com/privacy-policy/7930385/cookie-policy"} (tr :tr/cookie-policy)]
     (tr :tr/and)
     [:a.gray-link {:target "_blank"
                    :href   "https://www.iubenda.com/privacy-policy/7930385"} (tr :tr/privacy-policy)] "."]))

(comment
  (p/-> (routes/POST :account/sign-in {:account/email    ""
                                       :account/password "123123123"})
        js/console.log))

(ui/defview account:sign-in-form [{:keys [route]}]
  (ui/with-form [!account {:account/email    (?email :init "")
                           :account/password (?password :init "")}
                 :required [?email ?password]]
    (let [!step (h/use-state :email)]
      [:form.flex-grow.m-auto.gap-6.flex.flex-col.max-w-sm.px-4
       {:on-submit (fn [^js e]
                     (.preventDefault e)
                     (case @!step
                       :email (do (reset! !step :password)
                                  (js/setTimeout #(.focus (js/document.getElementById "account-password")) 100))
                       :password (p/let [res (routes/POST :account/sign-in @!account)]
                                   (js/console.log "res" res)
                                   (prn :res res))))}


       [:div.flex.flex-col.gap-2
        (ui/show-field ?email)
        (when (= :password @!step)
          (ui/show-field ?password {:id "account-password"}))
        (str (forms/visible-messages !account))
        [:button.btn.btn-primary.w-full.h-10.text-sm.p-3
         (tr :tr/sign-in)]]

       [:div.relative
        [:div.absolute.inset-0.flex.items-center [:span.w-full.border-t]]
        [:div.relative.flex.justify-center.text-xs.uppercase
         [:span.bg-secondary.px-2.text-muted-txt (tr :tr/or)]]]
       [account:sign-in-with-google]
       [account:sign-in-terms]])))

(ui/defview account:sign-in [params]
  [:div.h-screen.flex.flex-col

   [:div.flex.flex-col.flex-grow.items-center.max-w-sm.mt-10.relative.mt-24.mx-auto
    {:class ["bg-secondary rounded-t-lg border-t border-r border-l border-txt/05"]}
    [:div.w-full.p-3.flex.items-end
     [:div.flex-grow]
     [header:lang "absolute top-2 right-2"]]
    [:h1.text-3xl.font-medium.text-center.mt-2.mb-6 (tr :tr/welcome)]
    [account:sign-in-form params]]])

(ui/defview redirect [to]
  (h/use-effect #(routes/set-path! to)))

(ui/defview home [params]
  (if-let [account-id (db/get :env/account :entity/id)]
    #_[:a.btn.btn-primary.m-10.p-10 {:href (routes/path-for :org/index)} "Org/Index"]
    (redirect (routes/path-for :account/read {:account account-id}))
    (redirect (routes/path-for :account/sign-in params))))