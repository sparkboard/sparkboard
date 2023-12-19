(ns sb.app.account.ui
  (:require #?(:cljs ["@radix-ui/react-dropdown-menu" :as dm])
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.account.data :as data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.views.header :as header]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [tr]]
            [sb.icons :as icons]
            [sb.routing :as routes]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(ui/defview new-menu [params]
  (radix/dropdown-menu {:id       :new-menu
                        :trigger
                        [:div.btn-white (tr :tr/new) (icons/chevron-down:mini "ml-1 -mr-1 w-4 h-4")]
                        :children [[{:on-select #(routes/nav! 'sb.app.board-data/new params)} (tr :tr/board)]
                                   [{:on-select #(routes/nav! 'sb.app.org-data/new params)} (tr :tr/org)]]}))

(def down-arrow (icons/chevron-down:mini "ml-1 -mr-1 w-4 h-4"))

(ui/defview header [account child]
  (let [{:as          account
         :keys        [entity/id]
         display-name :account/display-name} account
        params  {:account-id [:entity/id id]}
        recents (when-let [recent-ids (data/recent-ids params)]
                  (filterv (comp recent-ids :entity/id)
                           (data/all params)))]
    [:div.header
     [:a.text-lg.font-semibold.leading-6.flex.flex-grow.items-center
      {:href (routes/path-for ['sb.app.account-ui/show params])} display-name]
     child

     (radix/dropdown-menu
       {:id       :show-recents
        :trigger  [:button (tr :tr/recent) down-arrow]
        :children (map (fn [entity]
                         [{:on-select #(routes/nav! (routes/entity-route entity 'show) entity)}
                          (:entity/title entity)])
                       recents)})
     (radix/dropdown-menu
       {:id :new
        :trigger  [:button (tr :tr/new) down-arrow]
        :children [[{:on-select #(routes/nav! 'sb.app.board-data/new params)} (tr :tr/board)]
                   [{:on-select #(routes/nav! 'sb.app.org-data/new params)} (tr :tr/org)]]})
     [header/chat]
     [header/account]]))

(defn account:sign-in-with-google []
  (v/x
    [:a.btn.btn-white
     {:class "w-full h-10 text-zinc-500 text-sm"
      :href  "/oauth2/google/launch"}
     [:img.w-5.h-5.m-2 {:src "/images/google.svg"}] (tr :tr/continue-with-google)]))

(defn account:sign-in-terms []
  (v/x
    [:p.px-8.text-center.text-sm {:class "text-txt/70"} (tr :tr/sign-in-agree-to)
     [:a.gray-link {:href "/documents/terms-of-service"} (tr :tr/tos)] ","
     [:a.gray-link {:target "_blank"
                    :href   "https://www.iubenda.com/privacy-policy/7930385/cookie-policy"} (tr :tr/cookie-policy)]
     (tr :tr/and)
     [:a.gray-link {:target "_blank"
                    :href   "https://www.iubenda.com/privacy-policy/7930385"} (tr :tr/privacy-policy)] "."]))

(ui/defview account:continue-with [{:keys [route]}]
  (ui/with-form [!account {:account/email    (?email :init "")
                           :account/password (?password :init "")}
                 :required [?email ?password]]
                (let [!step (h/use-state :email)]
                  [:form.flex-grow.m-auto.gap-6.flex-v.max-w-sm.px-4
                   {:on-submit (fn [^js e]
                                 (.preventDefault e)
                                 (case @!step
                                   :email (do (reset! !step :password)
                                              (js/setTimeout #(.focus (js/document.getElementById "account-password")) 100))
                                   :password (p/let [res (routes/POST 'sb.server.account/login! @!account)]
                                               (js/console.log "res" res)
                                               (prn :res res))))}


                   [:div.flex-v.gap-2
                    [field.ui/text-field ?email nil]
                    (when (= :password @!step)
                      [field.ui/text-field ?password {:id "account-password"}])
                    (str (forms/visible-messages !account))
                    [:button.btn.btn-primary.w-full.h-10.text-sm.p-3
                     (tr :tr/continue-with-email)]]

                   [:div.relative
                    [:div.absolute.inset-0.flex.items-center [:span.w-full.border-t]]
                    [:div.relative.flex.justify-center.text-xs.uppercase
                     [:span.bg-secondary.px-2.text-muted-txt (tr :tr/or)]]]
                   [account:sign-in-with-google]
                   [account:sign-in-terms]])))

(ui/defview sign-in
  {:route "/login"}
  [params]
  (if (db/get :env/config :account-id)
    (ui/redirect `home)
    [:div.h-screen.flex-v
     [header/lang "absolute top-0 right-0 p-4"]
     [:div.flex-v.items-center.max-w-sm.mt-10.relative.mx-auto.py-6.gap-6
      {:class ["bg-secondary rounded-lg border border-txt/05"]}
      [:h1.text-3xl.font-medium.text-center (tr :tr/welcome)]
      [radix/tab-root]
      [account:continue-with params]]]))

(ui/defview show
  {:route            "/"
   :endpoint/public? true}
  [params]
  (if (db/get :env/config :account-id)
    (let [?filter  (h/use-callback (forms/field))
          all      (data/all {})
          account  (db/get :env/config :account)
          title    (v/from-element :div.font-medium.text-xl.px-2)
          section  (v/from-element :div.flex-v.gap-2)
          entities (-> (group-by :entity/kind all)
                       (update-vals #(->> (sequence (ui/filtered @?filter) %)
                                          (sort-by :entity/created-at u/compare:desc)))
                       (u/guard seq))]
      [:div.divide-y
       [header account nil]

       (when-let [{:keys [org board project]} entities]
         [:div.p-body.flex-v.gap-8
          (when (> (count all) 6)
            [form.ui/filter-field ?filter nil])
          (let [limit (partial ui/truncate-items {:limit 10})]
            [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-2.md:gap-8.-mx-2
             (when (seq project)
               [section
                [title (tr :tr/projects)]
                (limit (map entity.ui/row project))])
             (when (seq board)
               [section
                [title (tr :tr/boards)]
                (limit (map entity.ui/row board))])
             (when (seq org)
               [section
                [title (tr :tr/orgs)]
                (limit (map entity.ui/row org))])])])
       [:div.p-body
        (when (empty? (:board entities))
          [ui/hero
           (ui/show-markdown
             (tr :tr/start-board-new))
           [:a.btn.btn-primary.btn-base {:class "mt-6"
                                         :href  (routes/path-for ['sb.app.board-data/new])}
            (tr :tr/create-first-board)]])]])
    (ui/redirect `sign-in)))