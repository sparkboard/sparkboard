(ns sb.app.project.new-flow
  (:require [inside-out.forms :as forms]
            [re-db.api :as db]
            [sb.app.board.data :as board.data]
            [sb.app.views.header :as header]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.authorize :as az]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.query :as q]
            [sb.routing :as routing]
            [sb.server.datalevin :as dl]
            [sb.validate :as validate]
            [yawn.hooks :as h]
            [yawn.view :as v]))


(q/defx new!
  {:prepare [az/with-account-id!]}
  [{:as what :keys [account-id board-id project]}]
  (validate/assert project [:map {:closed true} :entity/title :entity/parent])
  ;; TODO authorization
  (let [project (-> project
                    ;; Auth: board allows projects to be created by current user (must be a member)
                    (dl/new-entity :project :by account-id))]
    (db/transact! [project]))
  (select-keys project [:entity/id]))

(def form (v/from-element :div.flex-v.items-stretch.gap-6.items-start.outline-6.outline.outline-black.rounded-lg.p-6.flex-v.gap-6 {:on-submit (fn [^js e] (.preventDefault e))}))
(def submit (v/from-element :input.btn.btn-primary.px-6.py-3.self-start))
(def tag (v/from-element :div.inline-flex.items-center.gap-2.text-sm.outline.outline-1.outline-gray-300.rounded-lg.px-4.py-2.hover:bg-gray-100.cursor-pointer))
(def tag-detail (v/from-element :div.text-sm.outline-gray-300.flex.relative))
(def tag-detail-label (v/from-element :div.flex.items-center.gap-2.outline-1.outline-gray-300.outline.h-10.px-3.rounded-l-lg.whitespace-nowrap.flex-none {:class "min-w-[150px]"}))
(def tag-detail-input (v/from-element :input.h-10.flex-auto.px-3.form-text.rounded-r-lg.rounded-l-none.pr-4 {:placeholder "Add details..."}))
(def tag-icon (v/from-element :div))

(ui/defview inline-text-field [editor? ?field props]
  (let [style-classes "outline-none focus:outline-none m-0 py-[1px] px-0 text-sm"]
    (if-not editor?
      [:div (v/merge-props props {:class style-classes}) @?field]
      (ui/text-field ?field (v/merge-props props {:class [style-classes "cursor-text"]})))))

(def sample-help-wanted '[{?title "Logo design"}
                          {?title "Beta testers" ?detail "Android and iOS"}
                          {?title "Mentoring" ?detail "Fundraising"}])

(ui/defview start
  {:route "/b/:board-id/new"}
  [{:keys [board-id]}]
  (let [field      :div.flex-v.gap-3
        form-label :div.text-primary.text-sm.font-medium.flex.items-center
        form-hint  :div.text-gray-500.text-sm
        board      {:board-id board-id}]
    [:<>
     [header/entity (board.data/show board) nil]
     [:div.p-body.flex-v.gap-8

      [ui/show-markdown "Creating a new project is a multi-step process."]

      (ui/with-form [!project {:entity/title  ?title
                               :entity/parent board-id}]
        [:form
         {:ref       nil #_(ui/use-autofocus-ref)
          :on-submit (fn [e]
                       (.preventDefault e)
                       (ui/with-submission [result (new! {:project @!project})
                                            :form !project]
                         (prn result)
                         (routing/nav! 'sb.app.board.ui/show {:board-id board-id})))}
         [form


          [:h3 (t :tr/new-project)]


          [field
           [ui/text-field ?title]]

          ;; here is where we would loop through the "project fields" which are defined
          ;; in this board's settings.

          [field
           [form-label "What is the problem you're trying to solve?"]
           [ui/auto-size {:class       "form-text"
                          :placeholder "Type here..."}]]
          [field
           [form-label "How do you plan to solve it?"]
           [ui/auto-size {:class       "form-text"
                          :placeholder "Type here..."}]]

          [submit {:type "submit" :value (t :tr/next)}]]])

      [form
       [field
        [form-label "Looking for:"]
        [:div.grid.gap-3
         (for [[label emoji detail] [["Programmer" "💻" "We plan to use Rust"]
                                     ["Beta testers" "🧪" "Android and iOS, anyone interested in unicorns!"]
                                     ["Mentoring" "🧠" ""]]]
           [tag-detail {:key label}
            [tag-detail-label label [tag-icon emoji]]
            [tag-detail-input {:value detail :on-change #()}]
            [:div.absolute.right-0.top-0.bottom-0.flex.items-center.p-2.hover:bg-gray-100.rounded-r-lg.cursor-pointer
             [radix/dropdown-menu {:trigger [icons/ellipsis-horizontal "w-4 h-4"]
                                   :items   [[{:on-select #()} "Remove"]]}]]])]
        [:div.flex.gap-3
         [:input.form-text.w-80
          {:placeholder "What kind of help are you looking for?"}]
         [:div.btn.btn-white "Add"]]
        [form-hint "Suggestions"]
        [:div.flex.flex-wrap.gap-2
         [tag "Designer" [tag-icon "🎨"]]
         [tag " Project Manager" [tag-icon "📅"]]
         [tag "Investor intros" [tag-icon "💰"]]]]]

      "Empty: "

      [form
       [field
        [form-label "Help wanted:"]
        [:div.flex.gap-3
         [:input.form-text.w-80
          {:placeholder "What are you looking for?"}]
         [:div.btn.btn-white "Add"]]
        [:div.flex.flex-wrap.gap-3.text-gray-500.items-center
         [icons/plus "w-5 h-5 "]
         (map (partial vector :div.text-sm)
              ["Designer"
               "Programmer"
               "Mentoring"])]]]


      "Populated with items:"

      (ui/with-form [?items (?items :many {:title    (?title :init "")
                                           :detail   (?detail :init "")
                                           :complete (?complete :init false)}
                                    :init sample-help-wanted)]
        (let [editor? true]
          [form
           [field
            [form-label "Help wanted:"]
            [:div.flex-v.gap-2
             (for [{:as ?child :syms [?title ?detail ?complete]} ?items]
               [:div.flex.w-full.gap-2.items-start {:key (goog/getUid ?child)}
                [radix/dropdown-menu {:trigger [:div.hover:bg-gray-300.flex-center.rounded-full.w-7.h-7
                                                [icons/ellipsis-horizontal "w-4 h-4"]]
                                      :items   [[{:on-select #(swap! ?complete not)} (if @?complete "Activate" "Done")]
                                                [{:on-select #(forms/remove-many! ?child)} "Remove"]]}]
                [:div.flex-auto {:class (when @?complete "line-through text-gray-500")}
                 [inline-text-field editor? ?title]
                 (when-not @?complete
                   [inline-text-field editor? ?detail {:placeholder "Add details"
                                                       :class       "text-xs text-gray-500"}])]])]
            (let [blank    '{?title "" ?detail ""}
                  !new     (h/use-state blank)
                  !new-ref (h/use-ref)]
              [:form.flex.gap-2
               {:on-submit (fn [^js e]
                             (.preventDefault e)
                             (forms/add-many! ?items @!new)
                             (reset! !new blank)
                             (.focus @!new-ref))}
               [:input.form-text.w-80.font-medium
                {:ref         !new-ref
                 :placeholder "What are you looking for?"
                 :value       ('?title @!new)
                 :on-change   #(swap! !new assoc '?title (.. % -target -value))}]
               [:button.btn.btn-white {:type "submit"} "Add"]])

            ;; TODO show this only when input is focused
            #_[:div.flex.flex-wrap.gap-3.text-gray-500.items-center
               [icons/plus "w-5 h-5 "]
               (->> ["Designer"
                     "Programmer"
                     "Mentoring"]
                    (remove (into #{} (map :title @?items)))
                    (map (partial vector :div.text-sm)))]]]))

      "View for others (click to contact):"
      (ui/with-form [?items (?items :many {:title    (?title :init "")
                                           :detail   (?detail :init "")
                                           :complete (?complete :init false)}
                                    :init sample-help-wanted)]
        (let [editor? false]
          [form
           [field
            [form-label "Help wanted:"]
            [:div.flex-v.gap-2
             (for [{:as ?child :syms [?title ?detail ?complete]} ?items]
               [:div.flex.w-full.gap-2.items-start.group.cursor-pointer {:key (goog/getUid ?child)}
                (if editor?
                  [radix/dropdown-menu {:trigger [:div.hover:bg-gray-300.flex-center.rounded-full.w-7.h-7
                                                  [icons/ellipsis-horizontal "w-4 h-4"]]
                                        :items   [[{:on-select #(swap! ?complete not)} (if @?complete "Activate" "Done")]
                                                  [{:on-select #(forms/remove-many! ?child)} "Remove"]]}]
                  "•")
                [:div.flex-auto {:class (when @?complete "line-through text-gray-500")}
                 [inline-text-field editor? ?title]
                 (when-not @?complete
                   [inline-text-field editor? ?detail {:placeholder "Add details"
                                                       :class       "text-xs text-gray-500"}])]
                [icons/chat-bubble-oval-left "text-gray-300 group-hover:text-gray-500 w-5 h-5"]])]
            (when editor?
              (let [blank    '{?title "" ?detail ""}
                    !new     (h/use-state blank)
                    !new-ref (h/use-ref)]
                [:form.flex.gap-2
                 {:on-submit (fn [^js e]
                               (.preventDefault e)
                               (forms/add-many! ?items @!new)
                               (reset! !new blank)
                               (.focus @!new-ref))}
                 [:input.form-text.w-80.font-medium
                  {:ref         !new-ref
                   :placeholder "What are you looking for?"
                   :value       ('?title @!new)
                   :on-change   #(swap! !new assoc '?title (.. % -target -value))}]
                 [:button.btn.btn-white {:type "submit"} "Add"]]))
            ]]))
      ]]))


