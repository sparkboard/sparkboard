(ns sparkboard.views.ui
  (:require [inside-out.forms :as forms]
            [inside-out.macros]
            [re-db.react]
            [yawn.view :as v]
            [sparkboard.i18n :refer [tr]])
  (:require-macros [sparkboard.views.ui :refer [defview]]))



(def invalid-border-color "red")
(def invalid-text-color "red")
(def invalid-bg-color "light-pink")


(defview view-message [{:keys [type content]}]
  [:div
   {:style (case type
             (:error :invalid) {:color invalid-text-color
                                :background-color invalid-bg-color}
             nil)}
   content])

(defn input-text
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field attrs]
  (let [messages (forms/visible-messages ?field)]
    (v/x
     [:div
      [:input.form-input
       (v/props (:props (meta ?field))
                {:placeholder (:label ?field)
                 :value @?field
                 :on-change (forms/change-handler ?field)
                 :on-blur (forms/blur-handler ?field)
                 :on-focus (forms/focus-handler ?field)
                 :class (when (:invalid (forms/types messages))
                          "ring-2 ring-offset-2 ring-red-500 focus:ring-red-500")}
                attrs)]
      (when (seq messages)
        (into [:div.gap-3.text-xs] (map view-message messages)))])))


(defview input-checkbox
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field attrs]
  (let [messages (forms/visible-messages ?field)]
    [:<>
     [:input
      (v/props {:type "checkbox"
                :placeholder (:label ?field)
                :checked (boolean @?field)
                :on-change (fn [^js e] (.persist e) (js/console.log e) (reset! ?field (.. e -target -checked)))
                :on-blur (forms/blur-handler ?field)
                :on-focus (forms/focus-handler ?field)
                :class (when (:invalid (forms/types messages))
                         "ring-2 ring-offset-2 ring-red-500 focus:ring-red-500")}
               attrs)]
     (when (seq messages)
       (into [:div.mt-1] (map view-message) messages))]))

(def c:dark-button
  (v/classes ["inline-flex items-center justify-center "
              "font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none "
              "ring-offset-background bg-primary text-primary-foreground hover:bg-primary/90 "
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 "
              "rounded-md"]))

(def c:light-button
  (v/classes ["inline-flex items-center justify-center cursor-pointer"
              "font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none "
              "ring-offset-background border hover:border-primary/30 text-primary"
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 "
              "rounded-md px-3"]))

(def a:dark-button
  (v/from-element :a
    {:class
     ["inline-flex items-center justify-center "
      "font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none "
      "ring-offset-background bg-primary text-primary-foreground hover:bg-primary/90 "
      "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 "
      "rounded-md px-3"]}))

(def a:light-button
  (v/from-element :a
    {:class
     ["inline-flex items-center justify-center cursor-pointer"
      "font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none "
      "ring-offset-background border hover:border-primary/30 text-primary"
      "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 "
      "rounded-md px-3"]}))

(defn css-url [s] (str "url(" s ")"))

(forms/set-global-meta!
 {:account/email {:el input-text
                  :props {:type "email"
                          :placeholder (tr :tr/email)}
                  :validators [(fn [v _]
                                 (when-not (re-find #"^[^@]+@[^@]+$" v)
                                   (tr :tr/invalid-email)))]}
  :account/password {:el input-text
                     :props {:type "password"
                             :placeholder (tr :tr/password)}
                     :validators [(forms/min-length 8)]}
  :board/title {:props {:placeholder (tr :tr/board-title)}}})

(defn show-field [?field & [attrs]]
  (let [{:keys [el props]
         :or {el input-text}} (meta ?field)]
    (el ?field (v/props props attrs))))