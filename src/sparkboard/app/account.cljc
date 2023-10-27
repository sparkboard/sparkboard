(ns sparkboard.app.account
  (:require
    #?(:cljs ["@radix-ui/react-dropdown-menu" :as dm])
    #?(:cljs [sparkboard.ui.radix :as radix])
    #?(:cljs [yawn.hooks :as h])
    #?(:clj [sparkboard.server.account :as account])
    [clojure.pprint :refer [pprint]]
    [inside-out.forms :as forms]
    [promesa.core :as p]
    [re-db.api :as db]
    [sparkboard.authorize :as az]
    [sparkboard.entity :as entity]
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
                        [:div.btn-light (tr :tr/new) (icons/chevron-down-mini "ml-1 -mr-1 w-4 h-4")]}
                       [{:on-select #(routes/set-path! 'sparkboard.app.board/new params)} (tr :tr/board)]
                       [{:on-select #(routes/set-path! 'sparkboard.app.org/new params)} (tr :tr/org)]))

(ui/defview header [account entity child]
  (let [{:as          account
         :keys        [account-id]
         display-name :account/display-name} account]
    [:div.entity-header
     [:a.text-lg.font-semibold.leading-6.flex.flex-grow.items-center
      {:href (routes/href 'sparkboard.app.account/show {:account-id account-id})} display-name]
     child
     [new-menu {:account-id account-id}]
     [header/chat account]
     [header/account]]))

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

(ui/defview show
  {:route "/account"}
  [_]
  (let [?filter    (h/use-callback (forms/field))
        all        (db:all {})
        recents    (filterv (comp (db:recent-ids {}) :entity/id) all)
        account    (db/get :env/config :account)
        title      (v/from-element :div.font-medium.text-xl.px-2)]
    [:div
     [header account account nil]

     [:div.px-body.flex.flex-col.gap-8.mt-8
      [:div.flex.flex-col.gap-4
       [title (tr :tr/recent)]
       (into [:div.grid.grid-cols-1.sm:grid-cols-2.md:grid-cols-3.lg:grid-cols-4.gap-2]
             (map entity/row)
             recents)]
      [ui/filter-field ?filter]
      (let [{:keys [org board project]} (-> (group-by :entity/kind all)
                                            (update-vals #(->> (sequence (ui/filtered @?filter) %)
                                                               (sort-by :entity/created-at u/compare:desc))))
            section  (v/from-element :div.flex.flex-col.gap-2)
            limit (partial ui/truncate-items {:limit 10})]

        [:div.grid.grid-cols-1.md:grid-cols-2.lg:grid-cols-3.gap-8
         [section
          [title (tr :tr/projects)]
          (limit (map entity/row project))]
         [section
          [title (tr :tr/boards)]
          (limit (map entity/row board))]
         [section
          [title (tr :tr/orgs)]
          (limit (map entity/row org))]

         ])]]))


(defn account:sign-in-with-google []
  (v/x
    [:a.btn.btn-light
     {:class "w-full h-10 text-zinc-500 text-sm"
      :href  "/oauth2/google/launch"}
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
  (p/-> (routes/POST `sparkboard.app.account/sign-in
                     {:account/email    ""
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
                       :password (p/let [res (routes/POST 'sparkboard.server.account/login! @!account)]
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

(ui/defview sign-in
  {:route "/login"}
  [params]
  [:div.h-screen.flex.flex-col
   [header/lang "absolute top-0 right-0 p-4"]
   [:div.flex.flex-col.items-center.max-w-sm.mt-10.relative.mx-auto.py-6.gap-6
    {:class ["bg-secondary rounded-lg border border-txt/05"]}
    [:h1.text-3xl.font-medium.text-center (tr :tr/welcome)]
    [account:sign-in-form params]]])

(ui/defview home
  {:route            "/"
   :endpoint/public? true}
  [params]
  (if-let [account-id (db/get :env/config :account-id)]
    (show {:account-id account-id})
    (sign-in params)))

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
