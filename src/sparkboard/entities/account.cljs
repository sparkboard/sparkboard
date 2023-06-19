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

(defn lang-menu-content []
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
          (lang-menu-content))])

(ui/defview header:account []
  (if-let [account (db/get :env/account)]
    (radix/dropdown-menu
      {:trigger [:div.flex.items-center [:img.rounded-full.h-8.w-8 {:src (ui/asset-src (:image/avatar account) :avatar)}]]}
      [{:on-click #(routes/set-path! :home)} (tr :tr/home)]
      [{:on-click #(routes/set-path! :account/logout)} (tr :tr/logout)]
      (into [{:sub?    true
              :trigger [icons/languages "w-5 h-5"]}] (lang-menu-content)))
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routes/href :account/sign-in)} (tr :tr/sign-in)]))

(defn header [params & children]
  [:div.entity-header
   [:a.text-lg.font-semibold.leading-6.flex.flex-grow.items-center
    {:href (routes/href :account/read
                        {:account (or (:account params)
                                      (db/get :env/account :entity/id))})} (tr :tr/my-stuff)]
   children
   [header:account]])

(ui/defview new-menu [params]
  (radix/dropdown-menu {:trigger [:div.btn.btn-primary (tr :tr/new) (icons/chevron-down-mini "ml-1 -mr-1 w-4 h-4")]}
                       [{:on-select #(routes/set-path! :board/new params)} (tr :tr/board)]
                       [{:on-select #(routes/set-path! :org/new params)} (tr :tr/org)]))

(ui/defview read [{:as params :keys [data]}]
  (let [!tab    (h/use-state (tr :tr/recent))
        ?filter (h/use-callback (forms/field))]

    [:<>
     (header params)
     [radix/tab-root {:value           @!tab
                      :on-value-change (partial reset! !tab)}
      ;; tabs
      [:div.mt-6.flex.items-stretch.px-body.h-10.gap-3
       [radix/tab-list
        (->> [(tr :tr/recent)
              (tr :tr/all)]
             (map (fn [k]
                    [radix/tab-trigger
                     {:value k
                      :class "flex items-center"} k]))
             (into [:<>]))]
       [:div.flex-grow]
       [ui/filter-field ?filter]
       [new-menu params]]


      [radix/tab-content {:value (tr :tr/recent)}
       [entity/show-filtered-results {:q  @?filter
                                      :title   nil
                                      :results (:recents data)}]]
      [radix/tab-content {:value (tr :tr/all)}
       [entity/show-filtered-results {:q  @?filter
                                      :title   (tr :tr/orgs)
                                      :results (:org data)}]
       [entity/show-filtered-results {:q  @?filter
                                      :title   (tr :tr/boards)
                                      :results (:board data)}]
       [entity/show-filtered-results {:q  @?filter
                                      :title   (tr :tr/projects)
                                      :results (:project data)}]]]]))


(defn account:sign-in-with-google []
  (v/x
    [:a.btn.btn-light
     {:class "w-full h-10 text-zinc-500 text-sm"
      :href  (routes/href :oauth2.google/launch)}
     [:img.w-5.h-5.m-2 {:src "/images/google.svg"}] (tr :tr/sign-in-with-google)]))

(defn account:sign-in-terms []
  (v/x
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
   [header:lang "absolute top-0 right-0 p-4"]
   [:div.flex.flex-col.flex-grow.items-center.max-w-sm.mt-10.relative.mt-24.mx-auto
    {:class ["bg-secondary rounded-t-lg border-t border-r border-l border-txt/05"]}
    [:h1.text-3xl.font-medium.text-center.my-6 (tr :tr/welcome)]
    [account:sign-in-form params]]])

(ui/defview redirect [to]
  (h/use-effect #(routes/set-path! to)))

(ui/defview home [params]
  (if-let [account-id (db/get :env/account :entity/id)]
    #_[:a.btn.btn-primary.m-10.p-10 {:href (routes/path-for :org/index)} "Org/Index"]
    (redirect (routes/path-for :account/read {:account account-id}))
    (redirect (routes/path-for :account/sign-in params))))