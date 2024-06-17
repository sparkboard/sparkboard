(ns sb.app.org.ui
  (:require [clojure.edn :refer [read-string]]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [sb.app.domain-name.ui :as domain.ui]
            [sb.app.entity.data :as entity.data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.org.data :as data]
            [sb.app.views.header :as header]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [t]]
            [sb.routing :as routing]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(ui/defview show
  {:route "/o/:org-id"}
  [params]
  (forms/with-form [_ [?filter (?sort :init [:default])]]
    (let [{:as   org
           :keys [entity.data/description]} (data/show params)
          q     (ui/use-debounced-value (u/guard @?filter #(> (count %) 2)) 500)
          [result set-result!] (h/use-state nil)
          title (v/from-element :h3.font-medium.text-lg.pt-6)]
      (h/use-effect
        (fn []
          (when q
            (let [q q]
              (set-result! {:loading? true})
              (p/let [result (data/search-once {:org-id (:org-id params)
                                                :q      q})]
                (when (= q @?filter)
                  (set-result! {:value result
                                :q     q}))))))
        [q])
      [:div
       (header/entity org nil)
       [:div.p-body (field.ui/show-prose description)]
       [:div.p-body
        [:div.flex.gap-4.items-stretch
         ;; TODO filter field is not as tall as sort selection and new-project button, looks ugly
         [field.ui/filter-field ?filter {:loading? (:loading? result)}]
         [field.ui/select-field ?sort
          {:field/classes {:wrapper "flex-row items-center"}
           :field/label (t :tr/sort-order)
           :field/can-edit? true
           :field/wrap read-string
           :field/unwrap str
           :field/options [{:field-option/value [:default]
                            :field-option/label (t :tr/sort-default)}
                           {:field-option/value [:entity/created-at :direction :asc]
                            :field-option/label (t :tr/sort-entity-created-at-asc)}
                           {:field-option/value [:entity/created-at :direction :desc]
                            :field-option/label (t :tr/sort-entity-created-at-desc)}
                           {:field-option/value [:random]
                            :field-option/label (t :tr/sort-random)}]}]
         [:a.btn.btn-white.flex.items-center.px-3
          {:href (routing/path-for ['sb.app.board.ui/new-in-org
                                    {:parent (:entity/id org)}])}
          (t :tr/new-board)]]
        [ui/error-view result]
        (for [[kind results] (if (seq q)
                               (dissoc (:value result) :q)
                               [[:boards (:entity/_parent org)]])
              :when (seq results)]
          [:div.flex-v.gap-2
           [title (t (keyword "tr" (name kind)))]
           (into  [:div.grid.grid-cols-1.sm:grid-cols-2.md:grid-cols-3.lg:grid-cols-4.gap-2]
                  (comp (apply ui/sorted @?sort)
                        (map entity.ui/row))
                  results)])]])))

(ui/defview new
  {:route       "/new/o"
   :view/router :router/modal}
  [params]
  (forms/with-form [!org (u/prune
                           {:entity/title ?title})
                    :required [?title]]
    [:form
     {:class     form.ui/form-classes
      :on-submit (fn [e]
                   (.preventDefault e)
                   (ui/with-submission [result (data/new! {:org @!org})
                                        :form !org]
                     (routing/nav! [`show {:org-id (:entity/id result)}])))}
     [:h2.text-2xl (t :tr/new-org)]
     [field.ui/text-field ?title {:field/label (t :tr/title)
                                  :field/can-edit? true}]
     [form.ui/submit-form !org (t :tr/create)]]))
