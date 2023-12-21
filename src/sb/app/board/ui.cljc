(ns sb.app.board.ui
  (:require [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.account.data :as account.data]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.board.data :as data]
            [sb.app.domain-name.ui :as domain.ui]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.project.data :as project.data]
            [sb.app.project.ui :as project.ui]
            [sb.app.views.header :as header]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui :refer [tr]]
            [sb.routing :as routing]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(ui/defview new
  {:route       "/new/b"
   :view/router :router/modal}
  [{:as params :keys [route]}]
  (let [account (db/get :env/config :account)
        owners  (some->> (account.data/account-orgs {})
                         seq
                         (cons (account.data/account-as-entity account)))]
    (forms/with-form [!board (u/prune
                               {:entity/title       ?title
                                :entity/domain-name ?domain
                                :entity/parent      [:entity/id (uuid (?owner
                                                                        :init
                                                                        (or (-> params :query-params :org)
                                                                            (str (-> (db/get :env/config :account)
                                                                                     :entity/id)))))]})
                      :required [?title ?domain]]
      [:form
       {:class     form.ui/form-classes
        :on-submit (fn [^js e]
                     (.preventDefault e)
                     (ui/with-submission [result (data/new! {:board @!board})
                                          :form !board]
                                         (routing/nav! `show {:board-id (:entity/id result)})))
        :ref       (ui/use-autofocus-ref)}
       [:h2.text-2xl (tr :tr/new-board)]

       (when owners
         [:div.flex-v.gap-2
          [:label.field-label {} (tr :tr/owner)]
          (radix/select-menu {:value           @?owner
                              :on-value-change (partial reset! ?owner)
                              :options
                              (->> owners
                                   (map (fn [{:keys [entity/id entity/title image/avatar]}]
                                          {:value (str id)
                                           :text  title
                                           :icon  [:img.w-5.h-5.rounded-sm {:src (asset.ui/asset-src avatar :avatar)}]})))})])

       [field.ui/text-field ?title {:label (tr :tr/title)}]
       (domain.ui/domain-field ?domain nil)
       [form.ui/submit-form !board (tr :tr/create)]])))

(ui/defview register
  {:route "/b/:board-id/register"}
  [{:as params :keys [route]}]
  (ui/with-form [!member {:member/name ?name :member/password ?pass}]
    [:div
     [:h3 (tr :tr/register)]
     [field.ui/text-field ?name nil]
     [field.ui/text-field ?pass nil]
     [:button {:on-click #(p/let [res (routing/POST route @!member)]
                            ;; TODO - how to determine POST success?
                            #_(when (http-ok? res)
                                (routing/nav! [:board/read params])
                                res))}
      (tr :tr/register)]]))

(ui/defview action-button [{:as props :keys [on-click]} child]
  (let [!async-state (h/use-state nil)
        on-click     (fn [e]
                       (reset! !async-state {:loading? true})
                       (p/let [result (on-click e)]
                         (prn :res result)
                         (reset! !async-state (when (:error result) result))))
        {:keys [loading? error]} @!async-state]
    [radix/tooltip {:delay-duration 0} error
     [:div.btn.btn-white.overflow-hidden.relative
      (-> props
          (v/merge-props {:class (when error "ring-destructive ring-2")})
          (assoc :on-click
                 (when-not loading? on-click)))
      (tr :tr/new-project)
      (when (:loading? @!async-state)
        [:div.loading-bar.absolute.top-0.left-0.right-0.h-1])]]))

(ui/defview show
  {:route "/b/:board-id"}
  [{:as params :keys [board-id]}]
  (let [{:as board :keys [member/roles]} (data/show {:board-id board-id})
        !current-tab (h/use-state (tr :tr/projects))
        ?filter      (h/use-state nil)]
    [:<>
     [header/entity board]
     [:div.p-body

      [:div.flex.gap-4.items-stretch
       [form.ui/filter-field ?filter nil]
       [action-button
        {:on-click (fn [_]
                     (p/let [{:as   result
                              :keys [entity/id]} (project.data/new! nil
                                                                    {:entity/parent board-id
                                                                     :entity/title  (tr :tr/untitled)
                                                                     :entity/draft? true})]
                       (when id
                         (routing/nav! `project.ui/show {:project-id id}))
                       result))}
        (tr :tr/new-project)]]

      [radix/tab-root {:class           "flex flex-col gap-6 mt-6"
                       :value           @!current-tab
                       :on-value-change #(do (reset! !current-tab %)
                                             (reset! ?filter nil))}
       ;; tabs
       [:div.flex.items-stretch.h-10.gap-3
        [radix/show-tab-list
         (for [x [:tr/projects :tr/members] :let [x (tr x)]]
           {:title x :value x})]]

       [radix/tab-content {:value (tr :tr/projects)}
        (into [:div.grid]
              (comp (ui/filtered @?filter)
                    (map entity.ui/row))
              (data/projects {:board-id board-id}))]

       [radix/tab-content {:value (tr :tr/members)}
        (into [:div.grid]
              (comp (map #(merge (account.data/account-as-entity (:member/account %))
                                 (db/touch %)))
                    (map entity.ui/row))
              (data/members {:board-id board-id}))
        ]]]]))

(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])

