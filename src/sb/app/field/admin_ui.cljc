(ns sb.app.field.admin-ui
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [inside-out.forms :as io]
            [promesa.core :as p]
            [re-db.reactive :as r]
            [sb.app.entity.ui :as entity.ui :refer [view-field]]
            [sb.app.entity.data :as entity.data]
            [sb.app.field.ui :as field.ui]
            [sb.app.field.data :as data]
            [sb.app.form.ui :as form.ui]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.color :as color]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(defn element-center-y [el]
  #?(:cljs
     (j/let [^js {:keys [y height]} (j/call el :getBoundingClientRect)]
       (+ y (/ height 2)))))

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

(defn orderable-props
  [?child]
  #?(:cljs
     (let [?parent       (io/parent ?child)
           group         (goog/getUid ?parent)
           id            (:sym ?child)
           on-move       (fn [{:keys [source side destination]}]
                           (io/swap-many! ?parent re-order
                                          (get ?parent source)
                                          side
                                          (get ?parent destination))
                           (entity.data/save-field ?child))
           transfer-data (fn [e data]
                           (j/call-in e [:dataTransfer :setData] (str group)
                                      (pr-str data)))

           receive-data  (fn [e]
                           (try
                             (ui/read-string (j/call-in e [:dataTransfer :getData] (str group)))
                             (catch js/Error e nil)))
           data-matches? (fn [e]
                           (some #{(str group)} (j/get-in e [:dataTransfer :types])))
           [active-drag set-drag!] (h/use-state nil)
           [active-drop set-drop!] (h/use-state nil)
           !should-drag? (h/use-ref false)]
       {:drag-handle-props  {:on-mouse-down #(reset! !should-drag? true)
                             :on-mouse-up   #(reset! !should-drag? false)}
        :drag-subject-props {:draggable     true
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
        :drop-indicator     (when active-drop
                              (v/x [:div.absolute.bg-focus-accent
                                    {:class ["h-[4px] z-[99] inset-x-0 rounded"
                                             (case active-drop
                                               :before "top-[-2px]"
                                               :after "bottom-[-2px]" nil)]}]))})))

(ui/defview show-option [{:as ?option :syms [?label ?value ?color]}]
  (let [{:keys [drag-handle-props drag-subject-props drop-indicator]} (orderable-props ?option)]
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
     [field.ui/text-field ?label {:field/label         false
                                  :field/wrapper-class "flex-auto"
                                  :class               "rounded-sm relative focus:z-2"
                                  :style               {:background-color @?color
                                                  :color            (color/contrasting-text-color @?color)}}]
     [:div.relative.w-10.focus-within-ring.rounded.overflow-hidden.self-stretch
      [field.ui/color-field ?color {:style {:top -10
                                            :left -10
                                            :width 100
                                            :height 100
                                            :position "absolute"}}]]
     [radix/dropdown-menu {:id       :field-option
                           :trigger  [:button.p-1.relative.icon-gray.cursor-default.rounded.hover:bg-gray-200.self-stretch
                                      [icons/ellipsis-horizontal "w-4 h-4"]]
                           :children [[{:on-select (fn [_]
                                                     (radix/simple-alert! {:message      "Are you sure you want to remove this?"
                                                                           :confirm-text (t :tr/remove)
                                                                           :confirm-fn   (fn []
                                                                                           (io/remove-many! ?option)
                                                                                           (p/do (entity.data/save-field ?option)
                                                                                                 (radix/close-alert!)))}))}
                                       (t :tr/remove)]]}]]))

(ui/defview options-editor [?options]
  [:div.col-span-2.flex-v.gap-3
   [:label.field-label (t :tr/options)]
   (when (:loading? ?options)
     [:div.loading-bar.absolute.h-1.top-0.left-0.right-0])
   (into [:div.flex-v]
         (map show-option ?options))
   (let [?new (h/use-memo #(io/field :init ""))]
     [:form.flex.gap-2 {:on-submit (fn [^js e]
                                     (.preventDefault e)
                                     (io/add-many! ?options {'?value (str (random-uuid))
                                                             '?label @?new
                                                             '?color "#ffffff"})
                                     (io/try-submit+ ?new
                                       (p/let [result (entity.data/save-field ?options)]
                                         (reset! ?new (:init ?new))
                                         result)))}
      [field.ui/text-field ?new {:placeholder "Option label" :field/wrapper-class "flex-auto"}]
      [:div.btn.bg-white.px-3.py-1.shadow "Add Option"]])
   #_[ui/pprinted @?options]])

(ui/defview field-row-detail [{:as ?field :syms [?label
                                                 ?hint
                                                 ?options
                                                 ?type
                                                 ?required?
                                                 ?show-as-filter?
                                                 ?show-at-registration?
                                                 ?show-on-card?]}]
  (let [view-field (fn [?field & [props]]
                     (view-field ?field props))]
    [:div.bg-gray-100.gap-3.grid.grid-cols-2.pl-12.pr-7.pt-4.pb-6

     [:div.col-span-2.flex-v.gap-3
      (view-field ?label {:field/multi-line? true})
      (view-field ?hint {:field/multi-line? true
                         :placeholder       "Further instructions"})]

     (when (= :field.type/select @?type)
       [:div.col-span-2.text-sm
        (view-field ?options)])

     [:div.contents.labels-normal
      (view-field ?required?)
      (view-field ?show-as-filter?)
      (when (= :board/member-fields (:attribute (io/parent ?field)))
        (view-field ?show-at-registration?))
      (view-field ?show-on-card?)
      [:div
       [:a.p-1.-ml-1.-mt-1.text-sm.cursor-pointer.inline-flex.gap-2.rounded.hover:bg-gray-200
        {:on-click #(radix/simple-alert! {:message      "Are you sure you want to remove this?"
                                          :confirm-text (t :tr/remove)
                                          :confirm-fn   (fn []
                                                          (io/remove-many! ?field)
                                                          (entity.data/save-field ?field))})}
        [:div.w-5.h-5.rounded.flex.items-center.justify-center.text-destructive  [icons/trash "w-4 h-4"]]
        (t :tr/remove)]]]]))

(ui/defview field-row
  {:key (fn [{:syms [?id]} _] @?id)}
  [?field {:keys [expanded?
                  toggle-expand!]}]
  (let [{:syms [?type ?label]} ?field
        {:keys [icon]} (data/field-types @?type)
        {:keys [drag-handle-props
                drag-subject-props
                drop-indicator]} (orderable-props ?field)]
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
       [:div.w-5.-ml-5.mr-3.flex.items-center.justify-center.opacity-0.group-hover:opacity-50.flex-none
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
       (field-row-detail ?field))]))

(defn make-field:fields [init props]
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
            :init init))

(ui/defview fields-editor [{:as ?fields :keys [field/label]} props]
  (let [!new-field     (h/use-state nil)
        !autofocus-ref (ui/use-autofocus-ref)
        [expanded expand!] (h/use-state nil)]
    [:div.field-wrapper {:class "labels-semibold"}
     [:label.field-label {:class "flex items-center"}
      label
      [:div.flex.ml-auto.items-center
       (when-not @!new-field
         (radix/dropdown-menu {:id       :add-field
                               :trigger  [:div.text-sm.text-gray-500.font-normal.hover:underline.cursor-pointer.place-self-center
                                          "Add Field"]
                               :children (for [[type {:keys [icon field/label]}] data/field-types]
                                           [{:on-select #(let [id (random-uuid)]
                                                           (reset! !new-field
                                                                   (io/form {:field/id    id
                                                                             :field/type  ?type
                                                                             :field/label ?label}
                                                                            :init {:field/type type}
                                                                            :required [?label])))}
                                            [:div.flex.gap-4.items-center.cursor-default [icon "text-gray-600"] label]])}))]]
     [:div.flex-v.border.rounded.labels-sm
      (->> ?fields
           (map (fn [{:as ?field :syms [?id]}]
                  (field-row ?field
                             {:expanded?      (= expanded @?id)
                              :toggle-expand! #(expand! (fn [old]
                                                          (u/guard @?id (partial not= old))))})))
           doall)]
     (when-let [{:as   ?new-field
                 :syms [?type ?label]} @!new-field]
       [:div
        [:form.flex.gap-2.items-start.relative
         {:on-submit (ui/prevent-default
                       (fn [e]
                         (prn [:client1 @?new-field])
                         (io/add-many! ?fields @?new-field)
                         (prn [:client2 (last @?fields)])
                         (expand! (:field/id @?new-field))
                         (reset! !new-field nil)
                         (entity.data/save-field ?fields)))}
         [:div.h-10.flex.items-center [(:icon (data/field-types @?type)) "icon-lg text-gray-700  mx-2"]]
         [field.ui/text-field ?label {:field/label         false
                                      :ref                 !autofocus-ref
                                      :placeholder         (:field/label ?label)
                                      :field/wrapper-class "flex-auto"}]
         [:button.btn.btn-white.h-10 {:type "submit"}
          (t :tr/add)]
         [:div.flex.items-center.justify-center.icon-light-gray.h-10.w-7
          {:on-mouse-down #(reset! !new-field nil)}
          [icons/close "w-4 h-4 "]]]
        [:div.pl-12.py-2 (form.ui/show-field-messages ?new-field)]])]))