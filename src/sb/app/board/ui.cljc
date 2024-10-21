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
            [sb.app.membership.data :as member.data]
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
  (if (:account-id params)
    (h/use-effect (fn []
                    (p/let [membership (member.data/join! {:board-id (:board-id params)})]
                      (-> @routing/!location
                          (routing/update-matches `show {:board-id (:board-id params)})
                          (routing/update-matches `member.ui/show {:membership-id (:entity/id membership)})
                          routing/nav!*))))
    (h/use-effect (fn [] (routing/nav! `sb.app.account.ui/sign-in)))))

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

(ui/defview frame
  [{:as params :keys [board-id]} current-tab body]
  (let [board        (data/show {:board-id board-id})]
    [:div.flex-v.gap-6
     {:style (ui/background-image-style board)}
     [header/entity board nil]
     (when-not (:account-id params)
       [:div.max-w-prose.mx-auto.text-center.p-4.flex-v.gap-2.backdrop-blur-md.rounded-lg
        (when-let [src (asset.ui/asset-src (:image/logo-large board) :card)]
          [:img.mx-auto.my-4 {:class "w-2/3" :src src}])
        [field.ui/show-prose (:entity/description board)]
        [:div.flex.gap-3.justify-center
         [:a.btn.btn-primary.btn-base {:href (routing/path-for [`register {:board-id board-id}])}
          (t :tr/register)]
         [:a.btn.btn-white
          {:class "bg-white/40"
           :href (routing/path-for [`sb.app.account.ui/sign-in])}
          (t :tr/sign-in)]]
        [field.ui/show-prose (:board/home-page-message board)]])
     (ui/sub-header board)
     [:div.m-body.p-4.flex-v.gap-6.backdrop-blur-md.rounded-lg
      {:class "bg-white/20"}
      [:div {:class           "flex flex-col gap-6"}
       [:div.flex.justify-between.content-end
        ;; tabs
        (into [:div.flex.items-stretch.gap-3.mt-3]
              (map (fn [[title-kw path]]
                     (if (= title-kw current-tab)
                       [:div {:class ["px-1 border-b-2"
                                      "border-primary"
                                      "text-txt"]
                              :href (routing/entity-path board 'ui/show)}
                        (t title-kw)]
                       [:a {:class ["px-1 border-b-2 border-transparent text-txt/60"
                                    "hover:border-primary/10"]
                            :href path}
                        (t title-kw)])))
              (cons
               [:tr/projects (routing/entity-path board 'ui/show)]
               (when (:account-id params)
                 (cons
                  [:tr/members (routing/path-for [`members {:board-id board-id}])]
                  (when (:member-vote/open? board)
                    [[:tr/votes (routing/path-for [`voting {:board-id board-id}])]])))))
        (when-let [account (db/get :env/config :account)]
          (if-let [membership-id  (az/membership-id account board)]
            [:a.btn.btn-white
             {:class "bg-white/40"
              :href (routing/entity-path (db/entity membership-id) 'ui/show)}
             (t :tr/profile)]
            (when-let [join! (member.data/join!-authorized {:board-id board-id})]
              [ui/action-button
               {:class "bg-white/40"
                :on-click (fn [_]
                            (p/let [membership (join!)]
                              (routing/nav! `member.ui/show {:membership-id (:entity/id membership)})))}
               (t :tr/join)])))]
       body]]
     [:img.m-auto {:src (asset.ui/asset-src (:image/footer board) :page)}]]))

(ui/defview show
  {:route "/b/:board-id"
   :endpoint/public? true}
  [{:as params :keys [board-id]}]
  (let [board        (data/show {:board-id board-id})
        board-editor? (az/editor-role? (az/all-roles (:account-id params) board))
        tags (:entity/project-tags board)
        fields (:entity/project-fields board)
        card (partial project.ui/card
                      {:entity/project-tags tags
                       :entity/project-fields (filter :field/show-on-card? fields)})
        !project-filter (h/use-state nil)
        !xform (h/use-state (constantly identity))]
    [frame params :tr/projects
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
      (some->> (when (:account-id params)
                 (seq (data/project-drafts {:board-id board-id})))
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
           (grouped-card-grid card))]]))

(ui/defview members
  {:route "/b/:board-id/members"}
  [{:as params :keys [board-id]}]
  (let [board        (data/show {:board-id board-id})
        tags (:entity/project-tags board)
        fields (:entity/project-fields board)
        !xform (h/use-state (constantly identity))]
    [frame params :tr/members
     [:<>
      [:div.flex.flex-wrap.gap-4.items-end.mb-6
       [query-ui tags fields !xform]]
      (->> (data/members {:board-id board-id})
           (into [] @!xform )
           (grouped-card-grid (partial member.ui/card
                                       {:entity/member-tags tags
                                        :entity/member-fields (filter :field/show-on-card? fields)})))]]))

(ui/defview voting
  {:route "/b/:board-id/voting"}
  [{:as params :keys [board-id]}]
  (let [board        (data/show {:board-id board-id})
        tags (:entity/project-tags board)
        fields (:entity/project-fields board)
        !xform (h/use-state (constantly identity))]
    [frame params :tr/votes
     [:<>
      [:h2.text-2xl (t :tr/community-vote)]
      [:div.mb-4 (t :tr/vote-blurb)]
      [:div.flex.flex-wrap.gap-4.items-end.mb-6
       [query-ui tags fields !xform]]
      (->> (data/projects {:board-id board-id})
           (into [] @!xform)
           (grouped-card-grid project.ui/vote-card))]]))

(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])

