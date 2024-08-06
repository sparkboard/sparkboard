(ns sb.app.board.ui
  (:refer-clojure :exclude [read-string])
  (:require [clojure.edn :refer [read-string]]
            [inside-out.forms :as forms]
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

(ui/defview query-ui [tags fields !xform]
  (let [?match-filter  (h/use-memo #(io/field))
        !tag-filter    (h/use-state nil)
        !select-filter (h/use-state nil)
        ?sort          (h/use-memo #(io/field :init [:default]))]
    (h/use-effect #(reset! !xform
                           (comp (filter (apply u/every-pred*
                                                (concat (some-> @!tag-filter   uuid ui/tag-pred   vector)
                                                        (some-> @?match-filter      ui/match-pred vector)
                                                        (keep (fn [[k v]]
                                                                (when v
                                                                  (fn [entity]
                                                                    (= v (get-in entity
                                                                                 [:entity/field-entries
                                                                                  k
                                                                                  :select/value])))))
                                                              @!select-filter))))
                                 (apply ui/sorted @?sort)))
                  (h/use-deps [@?match-filter @!tag-filter @!select-filter @?sort]))
    [:<>
     (into [:<>
            (when (seq tags)
              [:div.field-wrapper
               [:label.field-label (t :tr/tag)]
               ;; TODO color tags here?
               [radix/toggle-group {:value @!tag-filter
                                    :on-change #(reset! !tag-filter %)
                                    :field/options (for [{:tag/keys [id label]} tags]
                                                     {:field-option/value (str id)
                                                      :field-option/label label})}]])]
           (comp (filter :field/show-as-filter?)
                 (map (fn [{:field/keys [id label options]}]
                        [:div.field-wrapper
                         [:label.field-label label]
                         [radix/toggle-group {:value (get @!select-filter id)
                                              :on-change #(swap! !select-filter assoc id %)
                                              :field/options options}]])))
           fields)
     [field.ui/select-field ?sort
      {:field/label (t :tr/sort-order)
       :field/can-edit? true
       :field/wrap read-string
       :field/unwrap str
       :field/options (into [{:field-option/value [:default]
                              :field-option/label (t :tr/sort-default)}
                             {:field-option/value [:entity/created-at :direction :asc]
                              :field-option/label (t :tr/sort-entity-created-at-asc)}
                             {:field-option/value [:entity/created-at :direction :desc]
                              :field-option/label (t :tr/sort-entity-created-at-desc)}
                             {:field-option/value [:random]
                              :field-option/label (t :tr/sort-random)}]
                            (comp (filter (every-pred :field/show-as-filter?
                                                      ;; TODO extend to all field types? In current dataset only `:field.type/select` is used.
                                                      (comp #{:field.type/select} :field/type)))
                                  (map (fn [{:field/keys [id label options]}]
                                         {:field-option/value [:field.type/select
                                                               :field-id id
                                                               :field-options options]
                                          :field-option/label label})))
                            fields)}]
     [field.ui/filter-field ?match-filter nil]]))

(ui/defview grouped-card-grid [card values]
  (into [:<>]
        (comp (partition-by (comp :group/label meta))
              (map (fn [group]
                     [:div.mb-6
                      (when-let [label (:group/label (meta (peek group)))]
                        [:h2.text-2xl.ml-4.mb-2.sticky.top-4.p-4.rounded-lg.inline-block.bg-white.z-10 label])
                      (into [:div.grid.gap-4.grid-cols-1.md:grid-cols-2.lg:grid-cols-3] (map card) group)])))
        values))

(ui/defview show
  {:route "/b/:board-id"}
  [{:as params :keys [board-id]}]
  (let [board        (data/show {:board-id board-id})
        !current-tab (h/use-state (t :tr/projects))]
    [:<>
     [header/entity board nil]
     [:div.p-body.flex-v.gap-6
      [radix/tab-root {:class           "flex flex-col gap-6 mt-6"
                       :value           @!current-tab
                       :on-value-change #(reset! !current-tab %)}
       ;; tabs
       [:div.flex.items-stretch.h-10.gap-3
        [radix/show-tab-list
         (for [x [:tr/projects :tr/members]
               :let [x (t x)]]
           {:title x :value x})]]

       [radix/tab-content {:value (t :tr/projects)}
        (let [tags (:entity/project-tags board)
              fields (:entity/project-fields board)
              card (partial project.ui/card
                            {:entity/project-tags tags
                             :entity/project-fields (filter :field/show-on-card? fields)})
              !xform (h/use-state (constantly identity))]
          [:<>
           [:div.flex.flex-wrap.gap-4.items-end.mb-6
            [query-ui tags fields !xform]
            [ui/action-button
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
           (some->> (seq (data/drafts {:board-id board-id}))
                    (into [:div.grid.border-b-2.border-gray-300.border-dashed.py-3.mb-3]
                          (map card)))
           (->> (data/projects {:board-id board-id})
                (into [] @!xform )
                (grouped-card-grid card))])]

       [radix/tab-content {:value (t :tr/members)}
        (let [tags (:entity/member-tags board)
              fields (:entity/member-fields board)
              !xform (h/use-state (constantly identity))]
          [:<>
           [:div.flex.flex-wrap.gap-4.items-end.mb-6
            [query-ui tags fields !xform]]
           (->> (data/members {:board-id board-id})
                (into [] @!xform )
                (grouped-card-grid (partial member.ui/card
                                            {:entity/member-tags tags
                                             :entity/member-fields (filter :field/show-on-card? fields)})))])]]]]))

(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])

