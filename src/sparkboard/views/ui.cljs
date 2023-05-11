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

(defn field-id [?field]
  (str "field-" (goog/getUid ?field)))

(defn pass-props [props] (dissoc props :multi-line :label :postfix :wrapper-class))

(defn text-props [?field]
  {:id (field-id ?field)
   :value (or @?field "")
   :on-change (fn [e]
                (js/console.log (.. e -target -checked))
                ((forms/change-handler ?field) e))
   :on-blur (forms/blur-handler ?field)
   :on-focus (forms/focus-handler ?field)})

(defn show-field-messages [?field]
  (when-let [messages (seq (forms/visible-messages ?field))]
    (v/x (into [:div.gap-3.text-sm] (map view-message messages)))))

(defn show-label [?field props]
  (when-let [label (or (:label props) (:label (meta ?field)))]
    [input-label {:for (field-id ?field)} label]))

(defn show-postfix [?field props]
  (when-let [postfix (or (:postfix props) (:postfix (meta ?field)))]
    [:div.pointer-events-none.absolute.inset-y-0.right-0.flex.items-center.pr-3 postfix]))

(defn input-text
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field props]
  (let [{:as props :keys [multi-line wrapper-class]} (merge props (:props (meta ?field)))]
    (v/x
      [:div.gap-2.flex.flex-col.relative
       {:class wrapper-class}
       (show-label ?field props)
       [:div.flex.relative
        [(if multi-line
           :textarea.form-text
           :input.form-text)
         (v/props (text-props ?field)
                  (pass-props props)
                  {:class (when (:invalid (forms/types (forms/visible-messages ?field)))
                            "outline-red-500")})]
        (show-postfix ?field props)]
       (show-field-messages ?field)])))

(defview input-checkbox
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field attrs]
  (let [messages (forms/visible-messages ?field)]
    [:<>
     [:input.h-5.w-5.rounded.border-gray-300.text-indigo-600.focus:outline-indigo-600
      (v/props {:type "checkbox"
                :on-blur (forms/blur-handler ?field)
                :on-focus (forms/focus-handler ?field)
                :on-change #(reset! ?field (.. ^js % -target -checked))
                :checked (or @?field false)
                :class (when (:invalid (forms/types messages))
                         "outline outline-offset-2 outline-2 outline-red-500 focus:outline-red-500")})]
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
