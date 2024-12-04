(ns sb.app.account.ui
  (:require #?(:cljs ["@radix-ui/react-dropdown-menu" :as dm])
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.account.data :as data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.membership.data :as member.data]
            [sb.app.views.header :as header]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [sb.authorize :as az]))

(defn account:sign-in-with-google []
  (v/x
    [:a.btn.btn-white
     {:class "w-full h-10 text-zinc-500 text-sm"
      :href  "/oauth2/google/launch"}
     [:img.w-5.h-5.m-2 {:src "/images/google.svg"}] (t :tr/continue-with-google)]))

(defn account:sign-in-terms []
  (v/x
    [:p.px-8.text-center.text-sm {:class "text-txt/70"} (t :tr/sign-in-agree-to)
     [:a.gray-link {:href "/documents/terms-of-service"} (t :tr/tos)] ","
     [:a.gray-link {:target "_blank"
                    :href   "https://www.iubenda.com/privacy-policy/7930385/cookie-policy"} (t :tr/cookie-policy)]
     (t :tr/and)
     [:a.gray-link {:target "_blank"
                    :href   "https://www.iubenda.com/privacy-policy/7930385"} (t :tr/privacy-policy)] "."]))

(ui/defview account:continue-with [{:keys [route]}]
  (ui/with-form [!account {:account/email    (?email :init "")
                           :account/password (?password :init "")}
                 :required [?email ?password]]
    (let [!step (h/use-state :email)]
      [:div.flex-grow.m-auto.gap-6.flex-v.max-w-sm.px-4
       [:div.flex-v.gap-2
        [field.ui/text-field ?email {:field/can-edit? true}]
        (when (= :password @!step)
          [field.ui/text-field ?password {:autoFocus true
                                          :field/can-edit? true}])
        (str (forms/visible-messages !account))
        [ui/action-button
         {:classes {:btn "btn-primary h-10 text-sm"}
          :on-click (fn [^js e]
                     (.preventDefault e)
                     (case @!step
                       :email (reset! !step :password)
                       :password (forms/try-submit+ !account
                                  (p/let [res (routing/POST 'sb.server.account/login! @!account)]
                                    ;; TODO put error messages in the right location
                                    (when-not (:error res)
                                      (set! js/window.location.href (routing/path-for `login-landing)))
                                    res))))}
         (t :tr/continue-with-email)]]

       [:div.relative
        [:div.absolute.inset-0.flex.items-center [:span.w-full.border-t]]
        [:div.relative.flex.justify-center.text-xs.uppercase
         [:span.bg-secondary.px-2.text-muted-txt (t :tr/or)]]]
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
      [:h1.text-3xl.font-medium.text-center (t :tr/welcome)]
      [radix/tab-root]
      [account:continue-with params]]]))

(ui/defview login-landing
  {:route "/login-landing"}
  [_]
  (h/use-effect (fn []
                  (routing/nav! (if-let [route @routing/!login-redirect]
                                  (do
                                    (reset! routing/!login-redirect nil)
                                    route)
                                  `home)))))

(ui/defview home
  {:route            "/"
   :endpoint/public? true}
  [{:keys [account-id]}]
  (if account-id
    (let [?filter       (h/use-callback (forms/field))
          all           (data/all {})
          account       (db/get :env/config :account)
          {:as entities :keys [board org]} (-> (->> all (group-by :entity/kind))
                                               (update-vals #(sort-by :entity/created-at u/compare:desc %))
                                               (u/guard seq))
          boards-by-org (group-by :entity/parent board)
          orgs (->> (keys boards-by-org)
                    (sort-by :entity/created-at u/compare:desc)
                    (into org)
                    distinct)
          match-text    @?filter]
      [:div.divide-y
       [header/entity (data/account-as-entity account) nil]

       (when (seq entities)
         [:div.p-body.flex-v.gap-8
          (when (> (count all) 6)
            [field.ui/filter-field ?filter nil])
          (let [limit (partial ui/truncate-items {:limit 10})]
            (when (seq orgs)
              [:div.flex-v.gap-4
               (u/for! [org orgs
                        :let [projects-by-board (into {}
                                                      (keep (fn [board]
                                                              (when-let [projects (->> (az/membership account-id board)
                                                                                       member.data/member-of
                                                                                       (into [] (ui/filtered @?filter))
                                                                                       seq)]
                                                                [board projects])))
                                                      (get boards-by-org org))
                              boards            (into []
                                                      (filter (fn [board]
                                                                (or (contains? projects-by-board board)
                                                                    (ui/match-entity match-text board))))
                                                      (get boards-by-org org))]
                        :when (or (seq boards)
                                  (seq projects-by-board)
                                  (ui/match-entity match-text org))]
                 [:div.contents {:key (:entity/id org)}
                  [:a.text-lg.font-semibold.flex.items-center.hover:underline {:href (routing/entity-path org 'ui/show)} (:entity/title org)]
                  (limit
                    (u/for! [board boards
                             :let [projects (get projects-by-board board)]]
                      [:div.flex-v
                       [entity.ui/row board]
                       [:div.pl-14.ml-1.flex.flex-wrap.gap-2.mt-2
                        (u/for! [project projects]
                          [:a.bg-gray-100.hover:bg-gray-200.rounded.h-12.inline-flex.items-center.px-3.cursor-default
                           ;; TODO modal does not load all the necessary data
                           {:href (routing/entity-path project 'ui/show)}
                           (:entity/title project)])]]))])]))])
       [:div.p-body
        (when (and (empty? match-text) (empty? (:board entities)))
          [ui/hero
           (ui/show-markdown
             (t :tr/start-board-new))
           [:a.btn.btn-primary.btn-base {:class "mt-6"
                                         :href  (routing/path-for ['sb.app.board.ui/new])}
            (t :tr/create-first-board)]])]])
    (ui/redirect `sign-in)))

(ui/defview show
  {:route "/a/:this-account-id"
   :view/router :router/modal}
  [params]
  (let [account (data/show params)
        memberships (group-by :entity/kind (member.data/member-of account))
        project-memberships (group-by :entity/parent (:project memberships))]
    [:div.p-6.flex-v.gap-3
     [:div.flex.gap-3
      (when (:image/avatar account) [ui/avatar {:size 20} account])
      [:div.flex-v.gap-2.grow
       [:h1.font-medium.text-2xl.flex-auto.flex.items-center.mt-2
        [entity.ui/persisted-attr account :account/display-name
         {:field/can-edit?   (= (:account-id params)
                                (:this-account-id params))
          :field/label       false
          :field/multi-line? false
          :field/unstyled?   (not (empty? (:account/display-name account)))}]]]
      [:a.btn.btn-white.flex.items-center.px-3.my-auto
       {:href (routing/path-for ['sb.app.chat.ui/chat {:other-id (:this-account-id params)}])}
       "message"]
      [:div.flex.px-1.rounded-bl-lg.border-b.border-l.absolute.top-0.right-0
       [radix/tooltip "Link to user"
        [:a.modal-title-icon {:href (routing/entity-path account 'ui/show)}
         [icons/link-2]]]
       [radix/dialog-close
        [:div.modal-title-icon [icons/close]]]]]
     [:div.field-label (t :tr/memberships)]
     (into [:div.flex-v.gap-6]
           (map (fn [board]
                  [:div
                   [:div.flex.gap-3
                    [:a.text-lg.hover:underline {:href (routing/entity-path board 'ui/show)}
                     (:entity/title board)]
                    [:a.btn.btn-white {:href (routing/entity-path (az/membership account board) 'ui/show)}
                     (t :tr/profile)]]
                   (into [:div.mt-3.flex.flex-wrap.gap-6]
                         (map (fn [project]
                                [:a.btn.btn-white {:href (routing/entity-path project 'ui/show)}
                                 (:entity/title project)]))
                         (project-memberships board))]))
           (:board memberships))]))

(ui/defview settings
  {:route "/settings"}
  [params]
  (let [account (data/settings nil)]
    [:<>
     [header/entity (data/account-as-entity account) nil]
     [:div.mx-auto.my-6 {:class "max-w-[600px]"}
      [:div.field-label.text-lg.pb-3 (t :tr/notification-settings)]
      [entity.ui/persisted-attr account :account/email-frequency {:field/can-edit? true}]]]))
