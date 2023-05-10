(ns sparkboard.views.ui
  (:require [inside-out.forms :as forms]
            [inside-out.macros]
            [re-db.react]
            [yawn.view :as v]
            [clojure.pprint :refer [pprint]])
  (:require-macros [sparkboard.views.ui :refer [defview tr]]))

(def logo-url "/images/logo-2023.png")

(def invalid-border-color "red")
(def invalid-text-color "red")
(def invalid-bg-color "light-pink")

(defview view-message [{:keys [type content]}]
  [:div
   {:style (case type
             (:error :invalid) {:color            invalid-text-color
                                :background-color invalid-bg-color}
             nil)}
   content])

(defview label [props content]
  [:label.text-sm.font-medium.leading-none.peer-disabled:cursor-not-allowed.peer-disabled:opacity-70 (v/props props)
   content])

(macroexpand
  '(yawn.view/x [(when true label) {:class "z"} "Hello"]))
(defn input-text
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field props]
  (let [messages (forms/visible-messages ?field)
        {:as props :keys [multi-line label]} (merge props (:props (meta ?field)))]
    (v/x
      [:div.gap-2.flex.flex-col
       [:div.flex
        [(if multi-line
           :textarea.form-text
           :input.form-text)
         (v/props (dissoc props :multi-line :label)
                  {:value       (or @?field "")
                   :on-change   (forms/change-handler ?field)
                   :on-blur     (forms/blur-handler ?field)
                   :on-focus    (forms/focus-handler ?field)
                   :class       (when (:invalid (forms/types messages))
                                  "ring-2 ring-offset-2 ring-red-500 focus:ring-red-500")})]
        [:div.w-8.flex.items-center.justify-center
         [:svg.animate-spin.h-5.w-5.text-blue-600.ml-2
          {:style   {:opacity    (if (forms/in-progress? ?field) 1 0)
                     :transition "0.1s linear opacity"}
           :xmlns   "http://www.w3.org/2000/svg"
           :fill    "none"
           :viewBox "0 0 24 24"}
          [:circle.opacity-25 {:cx "12" :cy "12" :r "10" :stroke "currentColor" :stroke-width "4"}]
          [:path.opacity-75 {:fill "currentColor" :d "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"}]]]]
       (when (seq messages)
         (into [:div.gap-3.text-xs] (map view-message messages)))])))


(defview input-checkbox
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field attrs]
  (let [messages (forms/visible-messages ?field)]
    [:<>
     [:input
      (v/props {:type        "checkbox"
                :placeholder (:label ?field)
                :checked     (boolean @?field)
                :on-change   (fn [^js e] (.persist e) (js/console.log e) (reset! ?field (.. e -target -checked)))
                :on-blur     (forms/blur-handler ?field)
                :on-focus    (forms/focus-handler ?field)
                :class       (when (:invalid (forms/types messages))
                               "ring-2 ring-offset-2 ring-red-500 focus:ring-red-500")}
               (dissoc attrs :label))]
     (when (seq messages)
       (into [:div.mt-1] (map view-message) messages))]))

(defn css-url [s] (str "url(" s ")"))

(defn show-field [?field & [attrs]]
  (let [{:keys [el props]
         :or   {el input-text}} (meta ?field)]
    (el ?field (v/merge-props props attrs))))

(defn pprinted [x]
  [:pre-wrap (with-out-str (pprint x))])

(def email-schema [:re #"^[^@]+@[^@]+$"])

(defn ^:dev/after-load init-forms []
  (forms/set-global-meta!
    (tr
      {:account/email    {:el         input-text
                          :props      {:type        "email"
                                       :placeholder :tr/email}
                          :validators [(fn [v _]
                                         (when v
                                           (when-not (re-find #"^[^@]+@[^@]+$" v)
                                             :tr/invalid-email)))]}
       :account/password {:el         input-text
                          :props      {:type        "password"
                                       :placeholder :tr/password}
                          :validators [(forms/min-length 8)]}
       :org/title        {:props {:placeholder :tr/title}}
       :org/description  {:el         input-text
                          :props {:multi-line true
                                  :placeholder :tr/description}}
       :board/title      {:props {:placeholder :tr/board-title}}})))
