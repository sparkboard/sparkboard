(ns sparkboard.app.account
  (:require
    #?(:cljs ["@radix-ui/react-dropdown-menu" :as dm])
    #?(:cljs [sparkboard.ui.radix :as radix])
    #?(:cljs [yawn.hooks :as h])
    #?(:clj [sparkboard.server.account :as account])
    [inside-out.forms :as forms]
    [promesa.core :as p]
    [re-db.api :as db]
    [sparkboard.authorize :as az]
    [sparkboard.app.entity :as entity]
    [sparkboard.i18n :refer [tr]]
    [sparkboard.routes :as routes]
    [sparkboard.schema :as sch :refer [?]]
    [sparkboard.ui :as ui]
    [sparkboard.ui.header :as header]
    [sparkboard.ui.icons :as icons]
    [sparkboard.util :as u]
    [sparkboard.query :as q]
    [yawn.view :as v]))

(sch/register!
  {:account/email               sch/unique-id-str
   :account/email-verified?     {:malli/schema :boolean}
   :account/display-name        {:malli/schema :string
                                 :db/fulltext  true}
   :account.provider.google/sub sch/unique-id-str
   :account/last-sign-in        {:malli/schema 'inst?}
   :account/password-hash       {:malli/schema :string}
   :account/password-salt       {:malli/schema :string}
   :account/locale              {:malli/schema :i18n/locale}
   :account/as-map              {:malli/schema [:map {:closed true}
                                                :entity/id
                                                :account/email
                                                :account/email-verified?
                                                :entity/created-at
                                                (? :account/locale)
                                                (? :account/last-sign-in)
                                                (? :account/display-name)
                                                (? :account/password-hash)
                                                (? :account/password-salt)
                                                (? :image/avatar)
                                                (? :account.provider.google/sub)]}})

(ui/defview new-menu [params]
  (radix/dropdown-menu {:trigger
                        [:div.btn-light (tr :tr/new) (icons/chevron-down:mini "ml-1 -mr-1 w-4 h-4")]}
                       [{:on-select #(routes/set-path! 'sparkboard.app.board/new params)} (tr :tr/board)]
                       [{:on-select #(routes/set-path! 'sparkboard.app.org/new params)} (tr :tr/org)]))

(q/defquery db:account-orgs
  {:endpoint {:query true}
   :prepare  az/with-account-id!}
  [{:keys [account-id]}]
  (into []
        (comp (map :member/entity)
              (filter (comp #{:org} :entity/kind))
              (map (q/pull entity/fields)))
        (db/where [[:member/account account-id]])))

(q/defquery db:all
  {:endpoint {:query true}
   :prepare  az/with-account-id!}
  [{:keys [account-id]}]
  (->> (q/pull '[{:member/_account [:member/roles
                                    :member/last-visited
                                    {:member/entity [:entity/id
                                                     :entity/kind
                                                     :entity/title
                                                     {:image/avatar [:asset/link
                                                                     :asset/id
                                                                     {:asset/provider [:s3/bucket-host]}]}
                                                     {:image/background [:asset/link
                                                                         :asset/id
                                                                         {:asset/provider [:s3/bucket-host]}]}]}]}]
               account-id)
       :member/_account
       (map #(u/lift-key % :member/entity))))

(q/defquery db:recent-ids
  {:endpoint {:query true}
   :prepare  az/with-account-id!}
  [params]
  (->> (db:all params)
       (filter :member/last-visited)
       (sort-by :member/last-visited #(compare %2 %1))
       (into #{} (comp (take 8)
                       (map :entity/id)))))

(defn account:sign-in-with-google []
  (v/x
    [:a.btn.btn-light
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

(def down-arrow (icons/chevron-down:mini "ml-1 -mr-1 w-4 h-4"))

(comment
  (p/-> (routes/POST `sparkboard.app.account/sign-in
                     {:account/email    ""
                      :account/password "123123123"})
        js/console.log))

(ui/defview header [account child]
  (let [{:as          account
         :keys        [entity/id]
         display-name :account/display-name} account
        params  {:account-id [:entity/id id]}
        recents (when-let [recent-ids (db:recent-ids params)]
                  (filterv (comp recent-ids :entity/id)
                           (db:all params)))]
    [:div.entity-header
     [:a.text-lg.font-semibold.leading-6.flex.flex-grow.items-center
      {:href (routes/href ['sparkboard.app.account/show params])} display-name]
     child
     (apply radix/dropdown-menu
            {:trigger
             [:div.btn-light (tr :tr/recent) down-arrow]}
            (map (fn [entity]
                   [{:on-select #(routes/set-path! (routes/entity-route entity 'show) entity)}
                    (:entity/title entity)])
                 recents))
     (radix/dropdown-menu {:trigger
                           [:div.btn-light (tr :tr/new) down-arrow]}
                          [{:on-select #(routes/set-path! 'sparkboard.app.board/new params)} (tr :tr/board)]
                          [{:on-select #(routes/set-path! 'sparkboard.app.org/new params)} (tr :tr/org)])
     [header/chat account]
     [header/account]]))

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
                       :password (p/let [res (routes/POST 'sparkboard.server.account/login! @!account)]
                                   (js/console.log "res" res)
                                   (prn :res res))))}


       [:div.flex-v.gap-2
        [ui/text-field ?email]
        (when (= :password @!step)
          [ui/text-field ?password {:id "account-password"}])
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
    (let [?filter (h/use-callback (forms/field))
          all     (db:all {})
          account (db/get :env/config :account)
          title   (v/from-element :div.font-medium.text-xl.px-2)
          section (v/from-element :div.flex-v.gap-2)]
      [:div.divide-y
       [header account nil]

       (when-let [{:keys [org board project]} (-> (group-by :entity/kind all)
                                                  (update-vals #(->> (sequence (ui/filtered @?filter) %)
                                                                     (sort-by :entity/created-at u/compare:desc)))
                                                  (u/guard seq))]
         [:div.p-body.flex-v.gap-8
          (when (> (count all) 6)
            [ui/filter-field ?filter])
          (let [limit (partial ui/truncate-items {:limit 10})]
            [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-2.md:gap-8.-mx-2
             (when (seq project)
               [section
                [title (tr :tr/projects)]
                (limit (map entity/row project))])
             (when (seq board)
               [section
                [title (tr :tr/boards)]
                (limit (map entity/row board))])
             (when (seq org)
               [section
                [title (tr :tr/orgs)]
                (limit (map entity/row org))])])])
       [:div.p-body
        [ui/hero
         (ui/show-markdown
           (tr :tr/start-board-new))
         [:a.btn.btn-primary.btn-base {:class "mt-6"
                                       :href  (routes/href ['sparkboard.app.board/new])}
          (tr :tr/create-first-board)]]]])
    (ui/redirect `sign-in)))

#?(:clj
   (defn login!
     {:endpoint         {:post ["/login"]}
      :endpoint/public? true}
     [req params]
     (account/login! req params)))

#?(:clj
   (defn logout!
     {:endpoint         {:get ["/logout"]}
      :endpoint/public? true}
     [req params]
     (account/logout! req params)))

#?(:clj
   (defn google-landing
     {:endpoint         {:get ["/oauth2/" "google/" "landing"]}
      :endpoint/public? true}
     [req params]
     (account/google-landing req params)))


(defn account-as-entity [account]
  (u/select-as account {:entity/id            :entity/id
                        :account/display-name :entity/title
                        :image/avatar         :image/avatar}))