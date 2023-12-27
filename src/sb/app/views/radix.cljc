(ns sb.app.views.radix
  (:require #?(:cljs ["@radix-ui/react-accordion" :as accordion])
            #?(:cljs ["@radix-ui/react-alert-dialog" :as alert])
            #?(:cljs ["@radix-ui/react-dialog" :as dialog])
            #?(:cljs ["@radix-ui/react-dropdown-menu" :as dm])
            #?(:cljs ["@radix-ui/react-select" :as sel])
            #?(:cljs ["@radix-ui/react-tabs" :as tabs])
            #?(:cljs ["@radix-ui/react-tooltip" :as tooltip])
            [sb.icons :as icons]
            [yawn.view :as v]
            [yawn.util]
            [sb.i18n :refer [t]]
            [re-db.reactive :as r]
            [sb.app.form.ui :as form.ui]
            [yawn.hooks :as h]))


(def menu-root (v/from-element :el dm/Root {:modal false}))
(def menu-sub-root (v/from-element :el dm/Sub))
(def menu-content-classes (v/classes ["rounded-sm bg-popover text-popover-txt  "
                                      "shadow-md ring-1 ring-txt/10"
                                      "focus:outline-none z-50"
                                      "gap-1 py-1 px-0"]))
(def menu-content (v/from-element :el dm/Content {:sideOffset        4
                                                  :collision-padding 16
                                                  :align             "start"
                                                  :class             menu-content-classes}))
(def menu-sub-content (v/from-element :el dm/SubContent {:class             menu-content-classes
                                                         :collision-padding 16
                                                         :sideOffset        0}))

(defn menu-item-classes [selected?]
  (str "block px-3 py-2 rounded mx-1 relative hover:outline-0 data-[highlighted]:bg-gray-100 cursor-default "
       (if selected?
         "text-txt/50 "
         (str "hover:bg-primary/5 "
              "data-[highlighted]:bg-primary/5 data-[highlighted]:outline-none"))))

(defn menu-item [props & children]
  (let [checks?   (contains? props :selected)
        selected? (:selected props)]
    (v/x
      [:el dm/Item (v/props {:class         [(menu-item-classes selected?)
                                             (when checks? "pl-8")]
                             :data-selected (:selected props false)}
                            (dissoc props :selected))
       (when checks?
         [:span.absolute.inset-y-0.left-0.flex.items-center.pl-2.text-txt.inline-flex
          {:class         "data-[selected=false]:hidden data-[highlighted]:bg-gray-100 hover:bg-gray-100"
           :data-selected (:selected props)}
          [icons/checkmark "h-4 w-4"]])
       children])))


(def menu-trigger (v/from-element :el dm/Trigger {:as-child true}))
(def menu-sub-trigger (v/from-element :el dm/SubTrigger {:class (menu-item-classes false)}))


(defn dropdown-menu [{:keys [id trigger sub? children] :or {id :radix-portal}}]
  (let [root-el    (if sub? menu-sub-root menu-root)
        trigger-el (if sub? menu-sub-trigger menu-trigger)
        content-el (if sub? menu-sub-content menu-content)]
    (v/x
      [root-el
       [trigger-el trigger]
       (into [content-el]
             (map (fn [[props & children]]
                    (if (:trigger props)
                      (dropdown-menu (assoc props :sub? true))
                      (into [menu-item props] children)))
                  children))])))

(v/defview select-item
  {:key :value}
  [{:keys [value text icon]}]
  (v/x
    [:el sel/Item {:class      (menu-item-classes false)
                   :value      value
                   :text-value text}
     [:el sel/ItemText [:div.flex.gap-2.py-2 icon text]]
     [:el sel/ItemIndicator]]))

(defn select-menu [{:as props :keys [id
                                     placeholder
                                     field/options
                                     field/can-edit?]
                    :or {id :radix-select}}]
  (v/x
    [:el sel/Root (cond-> (form.ui/pass-props (dissoc props :trigger :placeholder))
                          (not can-edit?)
                          (assoc :disabled true))
     [:el.btn.bg-white.flex.items-center.rounded.whitespace-nowrap.gap-1.group.default-ring.default-ring-hover.px-3 sel/Trigger
      {:class [(if can-edit?
                 "disabled:text-gray-400"
                 "text-gray-900")]}
      [:el sel/Value {:placeholder (v/x placeholder)}]
      [:div.flex-grow]
      (when can-edit?
        [:el.group-disabled:text-gray-400 sel/Icon (icons/chevron-down)])]
     [:el sel/Portal {:container (yawn.util/find-or-create-element id)}
      [:el sel/Content {:class menu-content-classes}
       [:el.p-1 sel/ScrollUpButton (icons/chevron-up "mx-auto")]
       (into [:el sel/Viewport {}] (map select-item) options)
       [:el.p-1 sel/ScrollDownButton (icons/chevron-down "mx-auto")]
       [:el sel/Arrow]]]]))

(def select-separator (v/from-element :el sel/Separator))
(def select-label (v/from-element :el sel/Label {:class "text-txt/70"}))
(def select-group (v/from-element :el sel/Group))

(defn dialog [{:props/keys [root
                            content]} & body]
  (v/x
    [:el dialog/Root (v/props root)
     [:el dialog/Portal
      [:el dialog/Overlay
       {:class "z-20 inset-0 fixed flex items-stretch md:items-start md:pt-[20px] justify-center backdrop-blur animate-appear bg-back/40 overflow-y-auto sm:grid "}
       [:el.bg-back.rounded-lg.shadow-lg.relative.outline-none.overflow-y-auto dialog/Content
        (v/props {:class "min-w-[350px] max-w-[900px]"} content)
        body
        #_[:el.outline-none.contents dialog/Close [:div.p-2.absolute.top-0.right-0.z-10 (icons/close "w-5 h-5")]]]]]]))

(def dialog-close (v/from-element :el.outline-none.contents dialog/Close))

(def tab-root (v/from-element :el tabs/Root))
(def tab-list (v/from-element :el.contents tabs/List))
(def tab-content (v/from-element :el.outline-none tabs/Content))
(def tab-trigger (v/from-element :el tabs/Trigger {:class ["px-1 border-b-2 border-transparent text-txt/50"
                                                           "data-[state=active]:cursor-pointer"
                                                           "data-[state=active]:border-primary"
                                                           "data-[state=active]:text-txt"
                                                           "data-[state=inactive]:hover:border-primary/10"
                                                           ]}))

(defn show-tab-list [tabs]
  [tab-list
   (->> tabs
        (map (fn [{:keys [title value]}]
               [tab-trigger
                {:value value
                 :class "flex items-center"} title]))
        (into [:<>]))])

(defonce !alert (r/atom nil))

(v/defview alert []
  (let [{:as   props
         :keys [title
                description
                body
                cancel
                action]
         :or   {cancel (t :tr/cancel)}} (h/use-deref !alert)]
    [:el alert/Root (v/props (merge @!alert
                                    {:on-open-change #(reset! !alert nil)}
                                    {:open (some? props)}))
     [:el alert/Portal
      [:el.overlay.z-9 alert/Overlay]
      [:el.overlay-content.z-4.rounded-lg.p-7.flex-v.gap-4.relative.z-10.bg-white alert/Content
       (when title [:el.font-bold alert/Title title])
       (when description [:el alert/Description description])
       body
       [:div.flex.gap-3.justify-end
        [:el alert/Cancel {:on-click #(reset! !alert nil)} cancel]
        [:el alert/Action action]]]]]))

(defn close-alert! [] (reset! !alert nil))
(defn open-alert! [props] (reset! !alert props))

(defn simple-alert! [{:keys [message
                             confirm-text
                             confirm-fn]}]
  (reset! !alert {:body   [:div.text-center.flex-v.gap-3
                           message]
                  :cancel [:div.btn.hover:bg-gray-100 (t :tr/cancel)]
                  :action [:div.btn.destruct
                           {:on-click (fn []
                                        (confirm-fn)
                                        (close-alert!))}
                           confirm-text]}))



(defn tooltip
  ([tip child] (tooltip {:delay-duration 200} tip child))
  ([props tip child]
   (if (seq tip)
     (v/x
       [:el tooltip/Provider props
        [:el tooltip/Root
         [:el.cursor-default tooltip/Trigger {:as-child true} child]
         [:el tooltip/Portal {:container (yawn.util/find-or-create-element "radix-tooltip")}
          [:el.px-2.py-1.shadow.text-white.text-sm.bg-gray-900.rounded.z-30 tooltip/Content {:align "center"
                                                                                             :style {:max-width 300}}
           tip
           [:el tooltip/Arrow]]]]])
     child))

  )

(defn accordion [props & sections]
  #?(:cljs
     [:el.accordion-root accordion/Root (v/merge-props {:default-value #js["0"]
                                                        :type          "multiple"}
                                                       props)
      (->> (partition 2 sections)
           (map-indexed
             (fn [i [trigger content]]
               [:el.accordion-item accordion/Item {:key   i
                                                   :value (str i)}
                [:el accordion/Header
                 [:el.accordion-trigger accordion/Trigger (v/x trigger) [icons/chevron-down]]]
                [:el.accordion-content accordion/Content
                 (v/x content)]])))]))