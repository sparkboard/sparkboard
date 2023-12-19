(ns sparkboard.app.field.ui
  (:require [applied-science.js-interop :as j]
            [clojure.set :as set]
            [clojure.string :as str]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.app.entity.data :as entity.data]
            [sparkboard.app.entity.ui :as entity.ui]
            [sparkboard.app.field-entry.ui :as entry.ui]
            [sparkboard.app.field.data :as data]
            [sparkboard.app.form.ui :as form.ui]
            [sparkboard.app.views.radix :as radix]
            [sparkboard.app.views.ui :as ui]
            [sparkboard.color :as color]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.icons :as icons]
            [sparkboard.schema :as sch]
            [sparkboard.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(defn element-center-y [el]
  #?(:cljs
     (j/let [^js {:keys [y height]} (j/call el :getBoundingClientRect)]
       (+ y (/ height 2)))))

(defn orderable-props
  [{:keys [group-id
           id
           on-move
           !should-drag?]}]
  #?(:cljs
     (let [transfer-data (fn [e data]
                           (j/call-in e [:dataTransfer :setData] (str group-id)
                                      (pr-str data)))

           receive-data  (fn [e]
                           (try
                             (ui/read-string (j/call-in e [:dataTransfer :getData] (str group-id)))
                             (catch js/Error e nil)))

           data-matches? (fn [e]
                           (some #{(str group-id)} (j/get-in e [:dataTransfer :types])))
           [active-drag set-drag!] (h/use-state nil)
           [active-drop set-drop!] (h/use-state nil)
           !should-drag? (h/use-ref false)]
       [{:on-mouse-down #(reset! !should-drag? true)
         :on-mouse-up   #(reset! !should-drag? false)}
        {:draggable     true
         :data-dragging active-drag
         :data-dropping active-drop
         :on-drag-over  (j/fn [^js {:as e :keys [clientY currentTarget]}]
                          (j/call e :preventDefault)
                          (when (data-matches? e)
                            (set-drop! (if (< clientY (element-center-y currentTarget))
                                         :before
                                         :after))))
         :on-drag-leave (fn [^js e]
                          (j/call e :preventDefault)
                          (set-drop! nil))
         :on-drop       (fn [^js e]
                          (.preventDefault e)
                          (set-drop! nil)
                          (when-let [source (receive-data e)]
                            (on-move {:destination id
                                      :source      source
                                      :side        active-drop})))
         :on-drag-end   (fn [^js e]
                          (set-drag! nil))
         :on-drag-start (fn [^js e]
                          (if @!should-drag?
                            (do
                              (set-drag! true)
                              (transfer-data e id))
                            (.preventDefault e)))}
        (when active-drop
          (v/x [:div.absolute.bg-focus-accent
                {:class ["h-[4px] z-[99] inset-x-0 rounded"
                         (case active-drop
                           :before "top-[-2px]"
                           :after "bottom-[-2px]" nil)]}]))])))

(defn re-order [xs source side destination]
  {:post [(= (count %) (count xs))]}
  (let [out (reduce (fn [out x]
                      (if (= x destination)
                        (into out (case side :before [source destination]
                                             :after [destination source]))
                        (conj out x)))
                    []
                    (remove #{source} xs))]
    (when-not (= (count out) (count xs))
      (throw (ex-info "re-order failed, destination not found" {:source source :destination destination})))
    out))

(ui/defview show-option [{:keys [?options save!]} {:as ?option :syms [?label ?value ?color]}]
  (let [[handle-props drag-props indicator]
        (orderable-props {:group-id (goog/getUid ?options)
                          :id       (:sym ?option)
                          :on-move  (fn [{:keys [source side destination]}]
                                      (forms/swap-many! ?options re-order
                                                        (get ?options source)
                                                        side
                                                        (get ?options destination))
                                      (save!))})]
    [:div.flex.gap-2.items-center.group.relative.-ml-6.py-1
     (merge {:key @?value}
            drag-props)
     [:div
      indicator
      [:div.flex.flex-none.items-center.justify-center.icon-gray
       (merge handle-props
              {:class ["w-6 -mr-2"
                       "opacity-0 group-hover:opacity-100"
                       "cursor-drag"]})
       [icons/drag-dots]]]
     [entry.ui/text-field ?label {:on-save       save!
                                  :wrapper-class "flex-auto"
                                  :class         "rounded-sm relative focus:z-2"
                                  :style         {:background-color @?color
                                                  :color            (color/contrasting-text-color @?color)}}]
     [:div.relative.w-10.focus-within-ring.rounded.overflow-hidden.self-stretch
      [entry.ui/color-field ?color {:on-save save!
                                    :style   {:top -10 :left -10 :width 100 :height 100 :position "absolute"}}]]
     [radix/dropdown-menu {:id       :field-option
                           :trigger  [:button.p-1.relative.icon-gray.cursor-default
                                      [icons/ellipsis-horizontal "w-4 h-4"]]
                           :children [[{:on-select (fn [_]
                                                     (radix/simple-alert! {:message      "Are you sure you want to remove this?"
                                                                           :confirm-text (tr :tr/remove)
                                                                           :confirm-fn   (fn []
                                                                                           (forms/remove-many! ?option)
                                                                                           (p/do (save!)
                                                                                                 (radix/close-alert!)))}))}
                                       (tr :tr/remove)]]}]]))

(ui/defview options-field [?field {:keys [on-save
                                          persisted-value]}]
  (let [memo-by [(str (map :field-option/value persisted-value))]
        {:syms [?options]} (h/use-memo (fn []
                                         (forms/form (?options :many {:field-option/label ?label
                                                                      :field-option/value ?value
                                                                      :field-option/color ?color}
                                                               :init (mapv #(set/rename-keys % '{:field-option/label ?label
                                                                                                 :field-option/value ?value
                                                                                                 :field-option/color ?color}) @?field))))
                                       memo-by)]

    (let [save! (fn [& _] (on-save (mapv u/prune @?options)))]
      [:div.col-span-2.flex-v.gap-3
       [:label.field-label (tr :tr/options)]
       (when (:loading? ?options)
         [:div.loading-bar.absolute.h-1.top-0.left-0.right-0])
       (into [:div.flex-v]
             (map (partial show-option {:?options ?options
                                        :save!    save!}) ?options))
       (let [?new (h/use-memo #(forms/field :init "") memo-by)]
         [:form.flex.gap-2 {:on-submit (fn [^js e]
                                         (.preventDefault e)
                                         (forms/add-many! ?options {'?value (str (random-uuid))
                                                                    '?label @?new
                                                                    '?color "#ffffff"})
                                         (forms/try-submit+ ?new (save!)))}
          [entry.ui/text-field ?new {:placeholder "Option label" :wrapper-class "flex-auto"}]
          [:div.btn.bg-white.px-3.py-1.shadow "Add Option"]])
       #_[ui/pprinted @?options]])))

(ui/defview field-editor-detail [parent attribute field]
  [:div.bg-gray-100.gap-3.grid.grid-cols-2.pl-12.pr-7.pt-4.pb-6

   [:div.col-span-2.flex-v.gap-3
    (entity.ui/use-persisted field :field/label entry.ui/text-field {:class      "bg-white text-sm"
                                                                     :multi-line true})
    (entity.ui/use-persisted field :field/hint entry.ui/text-field {:class       "bg-white text-sm"
                                                                    :multi-line  true
                                                                    :placeholder "Further instructions"})]

   (when (= :field.type/select (:field/type field))
     (entity.ui/use-persisted field :field/options options-field))
   #_[:div.flex.items-center.gap-2.col-span-2
      [:span.font-semibold.text-xs.uppercase (:label (field-types (:field/type field)))]]
   [:div.contents.labels-normal
    (entity.ui/use-persisted field :field/required? entry.ui/checkbox-field)
    (entity.ui/use-persisted field :field/show-as-filter? entry.ui/checkbox-field)
    (when (= attribute :board/member-fields)
      (entity.ui/use-persisted field :field/show-at-registration? entry.ui/checkbox-field))
    (entity.ui/use-persisted field :field/show-on-card? entry.ui/checkbox-field)
    [:a.text-gray-500.hover:underline.cursor-pointer.flex.gap-2
     {:on-click #(radix/simple-alert! {:message      "Are you sure you want to remove this?"
                                       :confirm-text (tr :tr/remove)
                                       :confirm-fn   (fn []
                                                       (data/remove-field nil
                                                                          (:entity/id parent)
                                                                          attribute
                                                                          (:entity/id field)))})}
     (tr :tr/remove)]]])

(ui/defview field-editor
  {:key (comp :entity/id :field)}
  [{:keys [parent attribute order-by expanded? toggle-expand! field]}]
  (let [field-type (data/field-types (:field/type field))
        [handle-props drag-props indicator] (orderable-props {:group-id attribute
                                                              :id       (sch/wrap-id (:entity/id field))
                                                              :on-move
                                                              (fn [{:as args :keys [source destination side]}]
                                                                (p/-> (entity.data/order-ref! {:attribute   attribute
                                                                                               :order-by    order-by
                                                                                               :source      source
                                                                                               :side        side
                                                                                               :destination destination})
                                                                      db/transact!))})]
    [:div.flex-v.relative.border-b
     ;; label row
     [:div.flex.gap-3.p-3.items-stretch.relative.group.cursor-default.relative
      (v/merge-props
        drag-props
        {:class    (if expanded?
                     "bg-gray-200"
                     "hover:bg-gray-100")
         :on-click toggle-expand!})
      indicator
      ;; expandable label group
      [:div.w-5.-ml-8.flex.items-center.justify-center.hover:cursor-grab.active:cursor-grabbing.opacity-0.group-hover:opacity-50
       handle-props
       [icons/drag-dots "icon-sm"]]
      [(:icon field-type) "cursor-drag icon-lg text-gray-700 self-center"]
      [:div.flex-auto.flex-v.gap-2
       (or (-> (:field/label field)
               (u/guard (complement str/blank?)))
           [:span.text-gray-500 (tr :tr/untitled)])]

      ;; expansion arrow
      [:div.flex.items-center.group-hover:text-black.text-gray-500.pl-2
       [icons/chevron-down:mini (str "w-4" (when expanded? " rotate-180"))]]]
     (when expanded?
       (field-editor-detail parent attribute field))]))

(ui/defview fields-editor [entity attribute]
  (let [label          (:label (forms/global-meta attribute))
        !new-field     (h/use-state nil)
        !autofocus-ref (ui/use-autofocus-ref)
        fields         (->> (get entity attribute)
                            (sort-by :field/order))
        [expanded expand!] (h/use-state nil)]
    [:div.field-wrapper {:class "labels-semibold"}
     (when-let [label (or label (tr attribute))]
       [:label.field-label {:class "flex items-center"}
        label
        [:div.flex.ml-auto.items-center
         (when-not @!new-field
           (radix/dropdown-menu {:id       :add-field
                                 :trigger  [:div.text-sm.text-gray-500.font-normal.hover:underline.cursor-pointer.place-self-center
                                            "Add Field"]
                                 :children (for [[type {:keys [icon label]}] data/field-types]
                                             [{:on-select #(reset! !new-field
                                                                   (forms/form {:field/type       ?type
                                                                                :field/label      ?label
                                                                                :field/published? (case type
                                                                                                    :field.type/select false
                                                                                                    true)}
                                                                               :init {'?type type}
                                                                               :required [?label]))}
                                              [:div.flex.gap-4.items-center.cursor-default [icon "text-gray-600"] label]])}))]])
     [:div.flex-v.border.rounded.labels-sm
      (->> fields
           (map (fn [field]
                  (field-editor {:parent         entity
                                 :attribute      attribute
                                 :order-by       :field/order
                                 :expanded?      (= expanded (:entity/id field))
                                 :toggle-expand! #(expand! (fn [old]
                                                             (when-not (= old (:entity/id field))
                                                               (:entity/id field))))
                                 :field          field})))
           doall)]
     (when-let [{:as   !form
                 :syms [?type ?label]} @!new-field]
       [:div
        [:form.flex.gap-2.items-start.relative
         {:on-submit (ui/prevent-default
                       (fn [e]
                         (forms/try-submit+ !form
                           (p/let [{:as result :keys [entity/id]} (data/add-field nil (:entity/id entity) attribute @!form)]
                             (expand! id)
                             (reset! !new-field nil)
                             result))))}
         [:div.flex.items-center.justify-center.absolute.icon-light-gray.h-10.w-7.-right-7
          {:on-click #(reset! !new-field nil)}
          [icons/close "w-5 h-5 "]]
         [:div.h-10.flex.items-center [(:icon (data/field-types @?type)) "icon-lg text-gray-700  mx-2"]]
         [entry.ui/text-field ?label {:label         false
                                      :ref           !autofocus-ref
                                      :placeholder   (:label ?label)
                                      :wrapper-class "flex-auto"}]
         [:button.btn.btn-white.h-10 {:type "submit"}
          (tr :tr/add)]]
        [:div.pl-12.py-2 (form.ui/show-field-messages !form)]])]))