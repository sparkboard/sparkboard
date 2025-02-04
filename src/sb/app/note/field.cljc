(ns sb.app.note.field
  "adapted from sb.app.field.admin_ui TODO see whether things can be merged"
  (:require [clojure.string :as str]
            [inside-out.forms :as io]
            [sb.app.entity.data :as entity.data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.entity.ui :refer [view-field]]
            [sb.app.field.data :as data]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.schema :as sch]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(ui/defview field-row-detail [{:as ?field :syms [?label
                                                 ?options
                                                 ?type
                                                 ?show-on-card?]}
                              ?field-entry
                              props]
  (let [view-field (fn [?field & [more-props]]
                     (view-field ?field (merge props more-props)))]
    [:div.bg-gray-100.gap-3.grid.grid-cols-2.pl-12.pr-7.pt-4.pb-6

     [:div.col-span-2.flex-v.gap-3
      (view-field ?label)]

     (when (= :field.type/select @?type)
       [:div.col-span-2.text-sm
        (view-field ?options)])

     ;; TODO don't show label here
     [:div.col-span-2
      [field.ui/show-entry-field ?field-entry props]]

     [:div.contents.labels-normal
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
  [?field
   ?field-entry
   {:as   props
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
       (field-row-detail ?field ?field-entry props))]))

(ui/defview fields-editor [entity props]
  (let [?fields (entity.ui/persisted-field entity :entity/fields
                                             (fn [init _props]
                                               (io/field :many (u/prune {:field/id            ?id
                                                                         :field/type          ?type
                                                                         :field/label         ?label
                                                                         :field/options (?options :many {:field-option/label ?label
                                                                                                         :field-option/value ?value
                                                                                                         :field-option/color ?color})
                                                                         :field/show-on-card? ?show-on-card?})
                                                         :init init))
                                             props)
        ?field-entries (entity.ui/persisted-field entity :entity/field-entries
                                                    field.ui/make-entries-?field
                                                    props)
        !new-field     (h/use-state nil)
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
                              :items   (for [[type {:keys [icon field/label]}] (dissoc data/field-types
                                                                                       :field.type/select)]
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
      (->> (map (fn [{:as ?field :syms [?id]} ?field-entry]
                  (field-row ?field
                             ?field-entry
                             (merge props
                                    #:field-row{:use-order      use-order
                                                :expanded?      (= expanded @?id)
                                                :toggle-expand! #(expand! (fn [old]
                                                                            (u/guard @?id (partial not= old))))})))
                ?fields (seq ('?entries ?field-entries)))
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

