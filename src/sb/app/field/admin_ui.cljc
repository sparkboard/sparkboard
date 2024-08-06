(ns sb.app.field.admin-ui
  (:require #?(:cljs ["@radix-ui/react-popover" :as Pop])
            [clojure.string :as str]
            [inside-out.forms :as io]
            [promesa.core :as p]
            [sb.app.entity.data :as entity.data]
            [sb.app.entity.ui :refer [view-field]]
            [sb.app.field.data :as data]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.color :as color]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(ui/defview show-option [{:as props :keys [option/use-order]}
                         {:as ?option :syms [?label ?value ?color]}]
  (let [{:keys [drag-handle-props drag-subject-props drop-indicator]} (use-order ?option)]
    [:div.flex.gap-2.items-center.group.relative.-ml-6.py-1
     (merge {:key @?value}
            drag-subject-props)
     [:div
      drop-indicator
      [:div.flex.flex-none.items-center.justify-center.icon-gray
       (merge drag-handle-props
              {:class ["w-6 -mr-2"
                       "opacity-0 group-hover:opacity-100"
                       "cursor-drag"]})
       [icons/drag-dots]]]
     [field.ui/text-field ?label {:field/label     false
                                  :field/can-edit? true
                                  :field/classes   {:wrapper "flex-auto"}
                                  :class           "rounded-sm relative focus:z-2"
                                  :style           {:background-color @?color
                                                    :color            (color/contrasting-text-color @?color)}}]
     [:div.relative.w-10.focus-within-ring.rounded.overflow-hidden.self-stretch
      [field.ui/color-field* ?color props]]
     [radix/dropdown-menu {:id      :field-option
                           :trigger [:button.p-1.relative.icon-gray.cursor-default.rounded.hover:bg-gray-200.self-stretch
                                     [icons/ellipsis-horizontal "w-4 h-4"]]
                           :items   [[{:on-select (fn [_]
                                                    (radix/simple-alert! {:message      (t :tr/remove?)
                                                                          :confirm-text (t :tr/remove)
                                                                          :confirm-fn   (fn []
                                                                                          (io/remove-many! ?option)
                                                                                          (p/do (entity.data/maybe-save-field ?option)
                                                                                                (radix/close-alert!)))}))}
                                      (t :tr/remove)]]}]]))

(ui/defview options-editor [?options props]
  (let [use-order (ui/use-orderable-parent ?options {:axis :y})]
    [:div.col-span-2.flex-v.gap-3
     [:label.field-label (t :tr/options)]
     (when (:loading? ?options)
       [:div.loading-bar.absolute.h-1.top-0.left-0.right-0])
     (into [:div.flex-v]
           (map (partial show-option (assoc props :option/use-order use-order)) ?options))
     (let [?new (h/use-memo #(io/field :init ""))]
       [:form.flex.gap-2 {:on-submit (fn [^js e]
                                       (.preventDefault e)
                                       (io/add-many! ?options {'?value (str (random-uuid))
                                                               '?label @?new
                                                               '?color "#ffffff"})
                                       (io/try-submit+ ?new
                                         (p/let [result (entity.data/maybe-save-field ?options)]
                                           (reset! ?new (:init ?new))
                                           result)))}
        [field.ui/text-field ?new {:placeholder     (t :tr/option-label)
                                   :field/can-edit? true
                                   :field/classes   {:wrapper "flex-auto"}}]
        [:button.btn.bg-white.px-3.py-1.shadow {:type "submit"} (t :tr/add-option)]])
     #_[ui/pprinted @?options]]))

(ui/defview field-row-detail [{:as ?field :syms [?label
                                                 ?hint
                                                 ?options
                                                 ?type
                                                 ?required?
                                                 ?show-as-filter?
                                                 ?show-at-registration?
                                                 ?show-on-card?]} props]
  (let [view-field (fn [?field & [more-props]]
                     (view-field ?field (merge props more-props)))]
    [:div.bg-gray-100.gap-3.grid.grid-cols-2.pl-12.pr-7.pt-4.pb-6

     [:div.col-span-2.flex-v.gap-3
      (view-field ?label)
      (view-field ?hint {:placeholder (t :tr/further-instructions)})]

     (when (= :field.type/select @?type)
       [:div.col-span-2.text-sm
        (view-field ?options)])

     [:div.contents.labels-normal
      (view-field ?required?)
      (view-field ?show-as-filter?)
      (when (= :entity/member-fields (:attribute (io/parent ?field)))
        (view-field ?show-at-registration?))
      (view-field ?show-on-card?)
      [:div
       [:a.p-1.-ml-1.-mt-1.text-sm.cursor-pointer.inline-flex.gap-2.rounded.hover:bg-gray-200
        {:on-click #(radix/simple-alert! {:message      (t :tr/remove?)
                                          :confirm-text (t :tr/remove)
                                          :confirm-fn   (fn []
                                                          (io/remove-many! ?field)
                                                          (entity.data/maybe-save-field ?field))})}
        [:div.w-5.h-5.rounded.flex-center.text-destructive [icons/trash "w-4 h-4"]]
        (t :tr/remove)]]]]))

(ui/defview field-row
  {:key (fn [{:syms [?id]} _] @?id)}
  [?field {:as   props
           :keys [field-row/expanded?
                  field-row/toggle-expand!
                  field-row/use-order]}]
  (let [{:syms [?type ?label]} ?field
        {:keys [icon]} (data/field-types @?type)
        {:keys [drag-handle-props
                drag-subject-props
                drop-indicator]} (use-order ?field)]
    [:div.flex-v.relative.border-b
     ;; label row
     [:div.flex.gap-3.p-3.items-stretch.relative.cursor-default.relative.group
      (v/merge-props
        drag-subject-props
        {:class    (if expanded? "bg-gray-200" "hover:bg-gray-100")
         :on-click toggle-expand!})
      drop-indicator
      ;; expandable label group
      [:div.relative.flex.-ml-3.-my-3.hover:cursor-grab.active:cursor-grabbing
       drag-handle-props
       [:div.w-5.-ml-5.mr-3.flex-center.opacity-0.group-hover:opacity-50.flex-none
        [icons/drag-dots "icon-sm"]]
       [icon "cursor-drag icon-lg text-gray-700 self-center flex-none"]]
      [:div.flex-auto.flex-v.gap-2
       (or (some-> @?label
                   (u/guard (complement str/blank?)))
           [:span.text-gray-500 (t :tr/untitled)])]

      ;; expansion arrow
      [:div.flex.items-center.group-hover:text-black.text-gray-500.pl-2
       [icons/chevron-down:mini (str "w-4" (when expanded? " rotate-180"))]]]
     (when expanded?
       (field-row-detail ?field props))]))

(ui/defview fields-editor
  {:make-?field (fn [init props]
                  (io/field :many (u/prune {:field/id                    ?id
                                            :field/type                  ?type
                                            :field/label                 ?label
                                            :field/hint                  ?hint
                                            :field/options               (?options :many {:field-option/label ?label
                                                                                          :field-option/value ?value
                                                                                          :field-option/color ?color})
                                            :field/required?             ?required?
                                            :field/show-as-filter?       ?show-as-filter?
                                            :field/show-at-registration? ?show-at-registration?
                                            :field/show-on-card?         ?show-on-card?})
                            :init init))}
  [?fields props]
  (let [!new-field     (h/use-state nil)
        !autofocus-ref (ui/use-autofocus-ref)
        [expanded expand!] (h/use-state nil)
        use-order      (ui/use-orderable-parent ?fields {:axis :y})]
    [:div.field-wrapper {:class "labels-semibold"}
     [:div.flex.items-center
      [form.ui/show-label ?fields nil]
      [:div.flex-auto]
      (when-not @!new-field
        (radix/dropdown-menu {:id      :add-field
                              :trigger [:div.text-sm.text-gray-500.font-normal.hover:underline.cursor-pointer.place-self-center
                                        "Add Field"]
                              :items   (for [[type {:keys [icon field/label]}] data/field-types]
                                         [{:class     "flex items-center h-8"
                                           :on-select #(let [id (random-uuid)]
                                                         (reset! !new-field
                                                                 (io/form {:field/id    id
                                                                           :field/type  ?type
                                                                           :field/label ?label}
                                                                          :init {:field/type type}
                                                                          :required [?label])))}
                                          [:div.flex.gap-3.items-center.cursor-default
                                           [icon "text-gray-600 -ml-1"]
                                           label]])}))]
     [:div.flex-v.border.rounded.labels-sm
      (->> ?fields
           (map (fn [{:as ?field :syms [?id]}]
                  (field-row ?field
                             (merge props
                                    #:field-row{:use-order      use-order
                                                :expanded?      (= expanded @?id)
                                                :toggle-expand! #(expand! (fn [old]
                                                                            (u/guard @?id (partial not= old))))}))))
           doall)]
     (when-let [{:as   ?new-field
                 :syms [?type ?label]} @!new-field]
       [:div
        [:form.flex.gap-2.items-start.relative
         {:on-submit (ui/prevent-default
                       (fn [e]
                         (io/add-many! ?fields @?new-field)
                         (expand! (:field/id @?new-field))
                         (reset! !new-field nil)
                         (entity.data/maybe-save-field ?fields)))}
         [:div.h-10.flex.items-center [(:icon (data/field-types @?type)) "icon-lg text-gray-700  mx-2"]]
         [field.ui/text-field ?label (merge props
                                            {:field/label   false
                                             :ref           !autofocus-ref
                                             :placeholder   (:field/label ?label)
                                             :field/classes {:wrapper "flex-auto"}})]
         [:button.btn.btn-white.h-10 {:type "submit"}
          (t :tr/add)]
         [:div.flex-center.icon-light-gray.h-10.w-7
          {:on-mouse-down #(reset! !new-field nil)}
          [icons/close "w-4 h-4 "]]]
        [:div.pl-12.py-2 (form.ui/show-field-messages ?new-field)]])]))

(ui/defview tags-editor
  {:make-?field (fn [init props]
                  (io/field :many (u/prune {:tag/id          ?id
                                            :tag/label       ?label
                                            :tag/color       ?color
                                            :tag/restricted? ?restricted?})
                            :init init))}
  [?tags field-props]
  (field.ui/plural-editor
    {:?items          ?tags
     :field/can-edit? true
     :make-?item      (fn [init props]
                        (let [id (random-uuid)]
                          (io/form (u/prune {:tag/id          (?id :default id)
                                             :tag/label       ?label
                                             :tag/color       (?color :default "#dddddd")
                                             :tag/restricted? (?restricted? :field/label (t :tr/restricted))})
                                   :required [?label]
                                   :init init)))
     :edit-?item      (fn [{:as ?item :syms [?label ?color ?restricted?]} submit!]
                        [:form.outline-none.flex-v.gap-2.items-stretch
                         {:on-submit (fn [e]
                                       (.preventDefault e)
                                       (submit!))}
                         [:div.flex.gap-2
                          [field.ui/text-field ?label (merge field-props
                                                             {:placeholder       (t :tr/label)
                                                              :field/keybindings {:Enter submit!}
                                                              :field/multi-line? false
                                                              :field/label       false})]
                          [:div.relative.w-10.h-10.overflow-hidden.rounded.outline.outline-black.outline-1
                           [field.ui/color-field* ?color field-props]]
                          [:button.flex.items-center {:type "submit"} [icons/checkmark "w-5 h-5 icon-gray"]]]
                         [field.ui/checkbox-field ?restricted? (merge field-props
                                                                      {:field/classes {:wrapper "pl-3"}})]])
     :show-?item      (fn [{:syms [?label ?color ?restricted?]} _props]
                        (let [bg    (or (u/some-str @?color) "#dddddd")
                              color (color/contrasting-text-color bg)]
                          (v/x [:div.rounded.bg-badge.text-badge-txt.py-1.px-2.text-sm.inline-flex.gap-1.items-center
                                {:key   @?label
                                 :style {:background-color bg :color color}}
                                @?label
                                (when @?restricted?
                                  [icons/lock:micro "w-3 h-3"])])))}))
