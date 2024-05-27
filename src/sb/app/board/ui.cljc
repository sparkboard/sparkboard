(ns sb.app.board.ui
  (:require [inside-out.forms :as forms]
            [inside-out.forms :as io]
            [promesa.core :as p]
            [re-db.api :as db]
            [sb.app.account.data :as account.data]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.board.data :as data]
            [sb.app.domain-name.ui :as domain.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.membership.ui :as member.ui]
            [sb.app.project.data :as project.data]
            [sb.app.project.ui :as project.ui]
            [sb.app.views.header :as header]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [t]]
            [sb.routing :as routing]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(ui/defview new-in-org
  {:route       "/new/b/:parent"
   :view/router :router/modal}
  [{:as params :keys [route parent]}]
  (let [account (db/get :env/config :account)
        owners  (some->> (account.data/account-orgs {})
                         seq
                         (cons (account.data/account-as-entity account)))]
    (forms/with-form [!board (u/prune
                               {:entity/title  ?title
                                :entity/parent [:entity/id (uuid (?owner
                                                                   :init
                                                                   (or parent
                                                                       (str (-> (db/get :env/config :account)
                                                                                :entity/id)))))]})
                      :required [?title]]
      [:form
       {:class     form.ui/form-classes
        :on-submit (fn [^js e]
                     (.preventDefault e)
                     (ui/with-submission [result (data/new! {:board @!board})
                                          :form !board]
                       (routing/nav! `show {:board-id (:entity/id result)})))
        :ref       (ui/use-autofocus-ref)}
       [:h2.text-2xl (t :tr/new-board)]
       (when owners
         [:div.flex-v.gap-2
          [:label.field-label (t :tr/owner)]
          (radix/select-menu {:value           @?owner
                              :on-value-change (partial reset! ?owner)
                              :field/can-edit? true
                              :field/options
                              (->> owners
                                   (map (fn [{:keys [entity/id entity/title image/avatar]}]
                                          {:value (str id)
                                           :text  title
                                           :icon  [:img.w-5.h-5.rounded-sm {:src (asset.ui/asset-src avatar :avatar)}]})))})])

       [field.ui/text-field ?title {:field/label (t :tr/title)
                                    :field/can-edit? true}]
       [form.ui/submit-form !board (t :tr/create)]])))

(routing/register-route new
                        {:alias-of    new-in-org
                         :route       "/new/b"
                         :view/router :router/modal})

(ui/defview register
  {:route "/b/:board-id/register"}
  [{:as params :keys [route]}]
  (ui/with-form [!membership {:membership/name     ?name
                              :membership/password ?pass}]
    [:div
     [:h3 (t :tr/register)]
     [field.ui/text-field ?name nil]
     [field.ui/text-field ?pass nil]
     [:button {:on-click #(p/let [res (routing/POST route @!membership)]
                            ;; TODO - how to determine POST success?
                            #_(when (http-ok? res)
                                (routing/nav! [:board/read params])
                                res))}
      (t :tr/register)]]))

(ui/defview action-button [{:as props :keys [on-click]} child]
  (let [!async-state (h/use-state nil)
        on-click     (fn [e]
                       (reset! !async-state {:loading? true})
                       (p/let [result (on-click e)]
                         (reset! !async-state (when (:error result) result))))
        {:keys [loading? error]} @!async-state]
    [radix/tooltip {:delay-duration 0} error
     [:div.btn.btn-white.overflow-hidden.relative
      (-> props
          (v/merge-props {:class (when error "ring-destructive ring-2")})
          (assoc :on-click (when-not loading? on-click)))
      (t :tr/new-project)
      (when (:loading? @!async-state)
        [:div.loading-bar.absolute.top-0.left-0.right-0.h-1])]]))

(ui/defview show
  {:route "/b/:board-id"}
  [{:as params :keys [board-id]}]
  (let [board        (data/show {:board-id board-id})
        !current-tab (h/use-state (t :tr/projects))
        ?filter      (h/use-memo #(io/field))
        card-grid    (v/from-element :div.grid.gap-4.grid-cols-1.md:grid-cols-2.lg:grid-cols-3)]
    [:<>
     [header/entity board nil]
     [:div.p-body.flex-v.gap-6

      [:div.flex.gap-4.items-stretch
       [field.ui/filter-field ?filter nil]
       [action-button
        {:on-click (fn [_]
                     (p/let [{:as   result
                              :keys [entity/id]} (project.data/new! nil
                                                                    {:entity/parent board-id
                                                                     :entity/title  (t :tr/untitled)
                                                                     :entity/admission-policy :admission-policy/open
                                                                     :entity/draft? true})]
                       (when id
                         (routing/nav! `project.ui/show {:project-id id}))
                       result))}
        (t :tr/new-project)]]

       [radix/tab-root {:class           "flex flex-col gap-6 mt-6"
                       :value           @!current-tab
                       :on-value-change #(do (reset! !current-tab %)
                                             (reset! ?filter nil))}
       ;; tabs
       [:div.flex.items-stretch.h-10.gap-3
        [radix/show-tab-list
         (for [x [:tr/projects :tr/members]
               :let [x (t x)]]
           {:title x :value x})]]


       [radix/tab-content {:value (t :tr/projects)}
        (some->> (seq (data/drafts {:board-id board-id}))
                 (into [:div.grid.border-b-2.border-gray-300.border-dashed.py-3.mb-3]
                       (map project.ui/card)))
        ;; TODO add sorting to filter-bar
        (->> (data/projects {:board-id board-id})
             (sort-by (complement :project/sticky?))
             (into [card-grid]
                   (comp (ui/filtered @?filter)
                         (map (partial project.ui/card
                                       {:entity/project-fields (filter :field/show-on-card? (:entity/project-fields board))})))))]

       [radix/tab-content {:value (t :tr/members)}
        (into [card-grid]
              (comp (ui/filtered @?filter)
                    (map (partial member.ui/card
                                  {:entity/member-tags   (:entity/member-tags board)
                                   :entity/member-fields (filter :field/show-on-card? (:entity/member-fields board))})))
              (data/members {:board-id board-id}))]]]]))

(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])

