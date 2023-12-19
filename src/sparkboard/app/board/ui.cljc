(ns sparkboard.app.board.ui
  (:require [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.app.account.data :as account.data]
            [sparkboard.app.asset.ui :as asset.ui]
            [sparkboard.app.board.data :as data]
            [sparkboard.app.domain.ui :as domain.ui]
            [sparkboard.app.entity.ui :as entity.ui]
            [sparkboard.app.field-entry.ui :as entry.ui]
            [sparkboard.app.field.ui :as field.ui]
            [sparkboard.app.form.ui :as form.ui]
            [sparkboard.app.project.data :as project.data]
            [sparkboard.app.project.ui :as project.ui]
            [sparkboard.app.form.ui :as form.ui]
            [sparkboard.app.views.header :as header]
            [sparkboard.app.views.radix :as radix]
            [sparkboard.app.views.ui :as ui]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routing :as routing]
            [sparkboard.util :as u]
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
                               {:entity/title  ?title
                                :entity/domain ?domain
                                :entity/parent [:entity/id (uuid (?owner
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

       [entry.ui/text-field ?title {:label (tr :tr/title)}]
       (domain.ui/domain-field ?domain)
       [form.ui/submit-form !board (tr :tr/create)]])))

(ui/defview register
  {:route "/b/:board-id/register"}
  [{:as params :keys [route]}]
  (ui/with-form [!member {:member/name ?name :member/password ?pass}]
    [:div
     [:h3 (tr :tr/register)]
     [entry.ui/text-field ?name]
     [entry.ui/text-field ?pass]
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
       [form.ui/filter-field ?filter]
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

(ui/defview settings
  {:route "/b/:board-id/settings"}
  [{:as params :keys [board-id]}]
  (let [board (data/settings params)]
    [:<>
     (header/entity board)
     [:div {:class form.ui/form-classes}
      (entity.ui/use-persisted board :entity/title entry.ui/text-field {:class "text-lg"})
      (entity.ui/use-persisted board :entity/description entry.ui/prose-field {:class "bg-gray-100 px-3 py-3"})
      (entity.ui/use-persisted board :entity/domain domain.ui/domain-field)
      (entity.ui/use-persisted board :image/avatar entry.ui/image-field {:label (tr :tr/image.logo)})

      (field.ui/fields-editor board :board/member-fields)
      (field.ui/fields-editor board :board/project-fields)

      ;; TODO
      ;; - :board/project-sharing-buttons
      ;; - :board/member-tags

      ;; Registration
      ;; - :board/registration-invitation-email-text
      ;; - :board/registration-newsletter-field?
      ;; - :board/registration-open?
      ;; - :board/registration-message
      ;; - :board/registration-url-override
      ;; - :board/registration-codes

      ;; Theming
      ;; - border radius
      ;; - headline font
      ;; - accent color

      ;; Sponsors
      ;; - logo area with tiered sizes/visibility

      ;; Sticky Notes
      ;; - schema: a new entity type (not a special kind of project)
      ;; - modify migration based on ^new schema
      ;; - color is picked per sticky note
      ;; - sticky notes can include images/videos

      ]]))

(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])

