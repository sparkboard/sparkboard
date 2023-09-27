(ns sparkboard.views.account
  (:require #?(:cljs ["@radix-ui/react-dropdown-menu" :as dm])
            #?(:cljs [sparkboard.views.radix :as radix])
            #?(:cljs [yawn.hooks :as h])
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [sparkboard.icons :as icons]
            [re-db.api :as db]
            [sparkboard.entity :as entity]
            [sparkboard.i18n :as i :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.views.ui :as ui]
            [sparkboard.websockets :as ws]
            [yawn.view :as v]))

(ui/defview header [child]
  (let [{account-id   :entity/id
         display-name :account/display-name} (db/get :env/account)]
    [:div.entity-header
     [:a.text-lg.font-semibold.leading-6.flex.flex-grow.items-center
      {:href (routes/href 'sparkboard.views.account/read {:account-id account-id})} display-name]
     child
     [ui/header:account]]))

(ui/defview new-menu [params]
  (radix/dropdown-menu {:trigger [:div.btn.btn-primary (tr :tr/new) (icons/chevron-down-mini "ml-1 -mr-1 w-4 h-4")]}
                       [{:on-select #(routes/set-path! 'sparkboard.views.board/new params)} (tr :tr/board)]
                       [{:on-select #(routes/set-path! 'sparkboard.views.org/new params)} (tr :tr/org)]))

#?(:clj
   (defn account:orgs
     {:endpoint  {:query ["/orgs"]}
      :authorize (fn [req params]
                   ;; TODO if no account, fail unauthenticated
                   (assoc params :account-id (-> req :account :entity/id)))}
     [{:keys [account-id]}]
     (into []
           (comp (map :member/entity)
                 (filter (comp #{:org} :entity/kind))
                 (map (db/pull entity/fields)))
           (db/where [[:member/account [:entity/id account-id]]]))))

#?(:clj
   (defn db:read
     {:endpoint  {:query ["/account"]}
      :authorize (fn [req params]
                   (assoc params :account-id (-> req :account :entity/id)))}
     [{:as params :keys [account-id]}]
     ;; TODO, ensure that the account in params is the same as the logged in user
     (let [entities (->> (db/pull '[{:member/_account [:member/roles
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
                                  [:entity/id account-id])
                         :member/_account
                         (map #(u/lift-key % :member/entity)))
           recents  (->> entities
                         (filter :member/last-visited)
                         (sort-by :member/last-visited #(compare %2 %1))
                         (take 8))]
       (merge {:recents recents}
              (group-by :entity/kind entities)))))

(ui/defview read
  {:endpoint {:view ["/account"]}}
  [_]
  (let [account-id (db/get :env/account :entity/id)
        query-data (ws/use-query! [`db:read {:account-id account-id}])
        !tab       (h/use-state (tr :tr/recent))
        ?filter    (h/use-callback (forms/field))]
    [:<>
     (header nil)
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
       [new-menu {:account-id account-id}]]


      [radix/tab-content {:value (tr :tr/recent)}
       [entity/show-filtered-results {:q       @?filter
                                      :title   nil
                                      :results (:recents query-data)}]]
      [radix/tab-content {:value (tr :tr/all)}
       [entity/show-filtered-results {:q       @?filter
                                      :title   (tr :tr/orgs)
                                      :results (:org query-data)}]
       [entity/show-filtered-results {:q       @?filter
                                      :title   (tr :tr/boards)
                                      :results (:board query-data)}]
       [entity/show-filtered-results {:q       @?filter
                                      :title   (tr :tr/projects)
                                      :results (:project query-data)}]]]]))


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
  (p/-> (routes/POST `sparkboard.views.account/sign-in
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

(ui/defview sign-in
  {:endpoint {:view ["/login"]}}
  [params]
  [:div.h-screen.flex.flex-col
   [ui/header:lang "absolute top-0 right-0 p-4"]
   [:div.flex.flex-col.items-center.max-w-sm.mt-10.relative.mx-auto.py-6.gap-6
    {:class ["bg-secondary rounded-lg border border-txt/05"]}
    [:h1.text-3xl.font-medium.text-center (tr :tr/welcome)]
    [account:sign-in-form params]]])


(ui/defview redirect [to]
  (h/use-effect #(routes/set-path! to)))

(ui/defview home
  {:endpoint {:view ["/"]}}
  [params]
  (if-let [account-id (db/get :env/account :entity/id)]
    #_[:a.btn.btn-primary.m-10.p-10 {:href (routes/path-for :org/index)} "Org/Index"]
    (redirect (routes/path-for 'sparkboard.views.account/read {:account-id account-id}))
    (redirect (routes/path-for 'sparkboard.views.account/sign-in params))))