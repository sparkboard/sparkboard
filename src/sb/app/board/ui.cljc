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
            [sb.app.note.data :as note.data]
            [sb.app.note.ui :as note.ui]
            [sb.app.project.data :as project.data]
            [sb.app.project.ui :as project.ui]
            [sb.app.views.header :as header]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.authorize :as az]
            [sb.i18n :refer [t]]
            [sb.routing :as routing]
            [sb.schema :as sch]
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
     (when (seq tags)
       [:div.field-wrapper
        [:label.field-label (t :tr/tag)]
        ;; TODO color tags here?
        [radix/toggle-group {:value @!tag-filter
                             :on-change #(reset! !tag-filter %)
                             :field/options (for [{:tag/keys [id label]} tags]
                                              {:field-option/value (str id)
                                               :field-option/label label})}]])
     (into [:<>]
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
  (into [:div.flex-v.gap-6]
        (comp (partition-by (comp :group/label meta))
              (map (fn [group]
                     [:div
                      (when-let [label (:group/label (meta (peek group)))]
                        [:h2.text-2xl.ml-4.mb-2.sticky.top-4.p-4.rounded-lg.inline-block.bg-white.z-10 label])
                      (into [:div.grid.gap-4.grid-cols-1.md:grid-cols-2.lg:grid-cols-3] (map card) group)])))
        values))

(ui/defview drafts [body]
  [:fieldset.border-t-2.border-b-2.border-gray-500.border-dashed.pt-2.pb-3
   [:legend.text-gray-700.px-2.ml-4 (t :tr/drafts)]
   body])

(ui/defview voting [{:keys [entity/id] :as board}]
  (let [tags (:entity/project-tags board)
        fields (:entity/project-fields board)
        !xform (h/use-state (constantly identity))]
    [:<>
     [:h2.text-2xl (t :tr/community-vote)]
     [:div.mb-4 (t :tr/vote-blurb)]
     [:div.flex.flex-wrap.gap-4.items-end.mb-6
      [query-ui tags fields !xform]]
     (->> (data/projects {:board-id (sch/wrap-id id)})
          (into [] @!xform)
          (grouped-card-grid project.ui/vote-card))]))

(ui/defview show
  {:route "/b/:board-id"}
  [{:as params :keys [board-id]}]
  (let [board        (data/show {:board-id board-id})
        !current-tab (h/use-state (t :tr/projects))
        board-editor? (az/editor-role? (az/all-roles (:account-id params) board))]
    [:div.flex-v.gap-6
     {:style (ui/background-image-style board)}
     [header/entity board nil]
     (ui/sub-header board)
     [:div.m-body.p-4.flex-v.gap-6.backdrop-blur-md.rounded-lg
      {:class "bg-white/20"}
      [radix/tab-root {:class           "flex flex-col gap-6 mt-2"
                       :value           @!current-tab
                       :on-value-change #(reset! !current-tab %)}
       ;; tabs
       [:div.flex.items-stretch.gap-3
        [radix/show-tab-list
         (for [x (cond-> [:tr/projects :tr/members]
                   (:member-vote/open? board)
                   (conj :tr/votes))
               :let [x (t x)]]
           {:title x :value x})]]

       [radix/tab-content {:value (t :tr/projects)}
        (let [tags (:entity/project-tags board)
              fields (:entity/project-fields board)
              card (partial project.ui/card
                            {:entity/project-tags tags
                             :entity/project-fields (filter :field/show-on-card? fields)})
              !project-filter (h/use-state nil)
              !xform (h/use-state (constantly identity))]
          [:div.flex-v.gap-6
           [:div.flex.flex-wrap.gap-4.items-end
            [:div.field-wrapper
             [:label.field-label (t :tr/filters)]
             [radix/toggle-group {:value @!project-filter
                                  :on-change #(reset! !project-filter %)
                                  :field/wrap read-string
                                  :field/unwrap str
                                  :field/options [{:field-option/label (t :tr/my-projects)
                                                   :field-option/value :my-projects}
                                                  {:field-option/label (t :tr/looking-for-help)
                                                   :field-option/value :looking-for-help}]}]]
            [query-ui tags fields !xform]
            (when-let [create! (note.data/new!-authorized {:note {:entity/parent board-id
                                                                :entity/title  (t :tr/untitled)
                                                                :entity/admission-policy :admission-policy/open
                                                                :entity/draft? true}})]
              [ui/action-button
               {:class "bg-white/40"
                :on-click (fn [_]
                            (p/let [{:as   result
                                     :keys [entity/id]} (create!)]
                              (when id
                                (routing/nav! `note.ui/show {:note-id id}))
                              result))}
               (t :tr/new-note)])
            (when-let [create! (project.data/new!-authorized {:project {:entity/parent board-id
                                                                      :entity/title  (t :tr/untitled)
                                                                      :entity/admission-policy :admission-policy/open
                                                                      :entity/draft? true}})]
              [ui/action-button
               {:class "bg-white/40"
                :on-click (fn [_]
                            (p/let [{:as   result
                                     :keys [entity/id]} (create!)]
                              (when id
                                (routing/nav! `project.ui/show {:project-id id}))
                              result))}
               (t :tr/new-project)])]

           ;; notes
           (some->> (when board-editor?
                      (seq (data/note-drafts {:board-id board-id})) )
                    (grouped-card-grid note.ui/card)
                    drafts)
           (grouped-card-grid note.ui/card (data/notes {:board-id board-id}))

           ;; projects
           (some->> (seq (data/project-drafts {:board-id board-id}))
                    (grouped-card-grid card)
                    drafts)
           (->> (data/projects {:board-id board-id})
                (into [] (comp (case @!project-filter
                                 :my-projects (filter #(some-> (db/get :env/config :account)
                                                               (az/membership-id board-id)
                                                               (az/membership-id %)))
                                 :looking-for-help (filter (comp seq :project/open-requests))
                                 nil identity)
                               @!xform))
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
                                             :entity/member-fields (filter :field/show-on-card? fields)})))])]
       (when (:member-vote/open? board)
         [radix/tab-content {:value (t :tr/votes)}
          [voting board]])]]
      [:img.m-auto {:src (asset.ui/asset-src (:image/footer board) :page)}]]))

(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])

