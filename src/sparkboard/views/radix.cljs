(ns sparkboard.views.radix
  (:require ["@radix-ui/react-dropdown-menu" :as dm]
            ["@radix-ui/react-select" :as sel]
            [sparkboard.icons :as icons]
            [yawn.view :as v]))

(def menu-root (v/from-element :el dm/Root))
(def menu-sub-root (v/from-element :el dm/Sub))
(def menu-portal (v/from-element :el dm/Portal))
(def menu-content-classes (v/classes ["rounded bg-popover text-popover-txt "
                                      "shadow-md ring-1 ring-txt/10"
                                      "focus:outline-none z-50"
                                      "text-sm gap-1 py-1"]))
(def menu-content (v/from-element :el dm/Content {:sideOffset        0
                                                  :collision-padding 16
                                                  :class             menu-content-classes}))
(def menu-sub-content (v/from-element :el dm/SubContent {:class             menu-content-classes
                                                         :collision-padding 16
                                                         :sideOffset        0}))

(defn menu-item-classes [selected?]
  (str "block px-2 py-1 rounded mx-1 relative "
       (if selected?
         "font-bold"
         "cursor-pointer hover:bg-primary hover:text-primary-txt")))


(defn menu-item [props & children]
  (let [checks?   (contains? props :selected)
        selected? (:selected props)]
    (v/x [:div (v/props {:class         [(menu-item-classes selected?)
                                         (when checks? "pl-8")]
                         :data-selected (:selected props false)}
                        (dissoc props :selected))
          (when checks?
            [:span.absolute.inset-y-0.left-0.flex.items-center.pl-2.text-txt.inline-flex
             {:class         "data-[selected=false]:hidden"
              :data-selected (:selected props)}
             [icons/checkmark "h-4 w-4"]])
          children])))


(def menu-trigger (v/from-element :el.focus-visible:outline-none.flex.items-stretch dm/Trigger))
(def menu-sub-trigger (v/from-element :el.focus-visible:outline-none dm/SubTrigger {:class (menu-item-classes false)}))

(defn dropdown-menu [{:keys [trigger sub?]} & children]
  (let [root-el    (if sub? menu-sub-root menu-root)
        trigger-el (if sub? menu-sub-trigger menu-trigger)
        content-el (if sub? menu-sub-content menu-content)]
    [root-el
     [trigger-el trigger]
     [menu-portal
      (into [content-el]
            (map (fn [[props & children]]
                   (if (:trigger props)
                     (apply dropdown-menu (assoc props :sub? true) children)
                     [menu-item props children]))
                 children))]]))

(defn select-menu [{:as props :keys [trigger]} & children]
  (v/x
    [:el sel/Root (v/props (dissoc props :trigger))
     [:el.form-text.flex sel/Trigger
      [:el sel/Value {:placeholder "Select an organization..."}]
      [:div.flex-grow]
      [:el sel/Icon (icons/chevron-down "w-5 h-5")]]
     [:el sel/Portal
      [:el.bg-back.rounded-lg.shadow sel/Content
       [:el sel/ScrollUpButton "Up"]
       (into [:el sel/Viewport {}] children)
       [:el sel/ScrollDownButton "Down"]]]]))

(def select-separator (v/from-element :el sel/Separator))
(def select-label (v/from-element :el sel/Label {:class "text-txt/70"}))
(def select-group (v/from-element :el sel/Group))
(v/defview select-item
  {:key (fn [value _] value)}
  [value text]
  (v/x [:el sel/Item {:class (menu-item-classes false)
                      :value value}
        [:el sel/ItemText text]
        [:el sel/ItemIndicator]]))