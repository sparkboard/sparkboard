(ns sparkboard.views.ui
  (:require [inside-out.forms :as forms]
            [inside-out.macros]
            [re-db.react]
            [yawn.view :as v]
            [clojure.pprint :refer [pprint]]
            [sparkboard.schema :as schema])
  (:require-macros [sparkboard.views.ui :refer [defview tr]]))

(def logo-url "/images/logo-2023.png")

(def invalid-border-color "red")
(def invalid-text-color "red")
(def invalid-bg-color "light-pink")

(def loader
  (v/x
    [:div.flex.items-center.justify-left
     [:svg.animate-spin.h-4.w-4.text-blue-600.ml-2
      {:xmlns "http://www.w3.org/2000/svg"
       :fill "none"
       :viewBox "0 0 24 24"}
      [:circle.opacity-25 {:cx "12" :cy "12" :r "10" :stroke "currentColor" :stroke-width "4"}]
      [:path.opacity-75 {:fill "currentColor" :d "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"}]]]))

(defview view-message [{:keys [type content]}]
  (case type
    :in-progress loader
    [:div
     {:style (case type
               (:error :invalid) {:color invalid-text-color
                                  :background-color invalid-bg-color}
               nil)}
     content]))

(defview input-label [props content]
  [:label.block.text-sm.font-medium.leading-6.text-gray-900 (v/props props)
   content])

(defn field-props [?field]
  {:value (or @?field "")
   :on-change (forms/change-handler ?field)
   :on-blur (forms/blur-handler ?field)
   :on-focus (forms/focus-handler ?field)
   :class (when (:invalid (forms/types (forms/visible-messages ?field)))
            "outline-red-500")})

(defn input-text
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field props]
  (let [messages (forms/visible-messages ?field)
        id (str "field-" (goog/getUid ?field))
        {:as props :keys [multi-line label postfix wrapper-class]} (merge props (:props (meta ?field)))]
    (v/x
      [:div.gap-2.flex.flex-col.relative
       {:class wrapper-class}
       (when label [input-label {:for id} label])
       [:div.flex.relative
        [(if multi-line
           :textarea.form-text
           :input.form-text)
         (v/props (dissoc props :multi-line :label :postfix :wrapper-class)
                  {:id id}
                  (field-props ?field))]
        (when postfix
          [:div.pointer-events-none.absolute.inset-y-0.right-0.flex.items-center.pr-3 postfix])]
       (when (seq messages)
         (into [:div.gap-3.text-sm] (map view-message messages)))])))


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
               (dissoc attrs :label))]
     (when (seq messages)
       (into [:div.mt-1] (map view-message) messages))]))

(defn css-url [s] (str "url(" s ")"))

(defn show-field [?field & [attrs]]
  (let [{:keys [el props]
         :or {el input-text}} (meta ?field)]
    (el ?field (v/merge-props props attrs))))

(defn pprinted [x]
  [:pre-wrap (with-out-str (pprint x))])

(def email-schema [:re #"^[^@]+@[^@]+$"])

(comment
  (defn malli-validator [schema]
    (fn [v _]
      (vd/humanized schema v))))

(defn ^:dev/after-load init-forms []
  #_(when k
      (let [validator (some-> schema/sb-schema (get k) :malli/schema malli-validator)]
        (cond-> (k field-meta)
                validator
                (update :validators conj validator))))
  (forms/set-global-meta!
    (tr
      {:account/email {:el input-text
                       :props {:type "email"
                               :placeholder :tr/email}
                       :validators [(fn [v _]
                                      (when v
                                        (when-not (re-find #"^[^@]+@[^@]+$" v)
                                          :tr/invalid-email)))]}
       :account/password {:el input-text
                          :props {:type "password"
                                  :placeholder :tr/password}
                          :validators [(forms/min-length 8)]}}))
  )
