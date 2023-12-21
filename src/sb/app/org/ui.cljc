(ns sb.app.org.ui
  (:require [inside-out.forms :as forms]
            [promesa.core :as p]
            [sb.app.domain-name.ui :as domain.ui]
            [sb.app.entity.data :as entity.data]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.org.data :as data]
            [sb.app.views.header :as header]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [tr]]
            [sb.routing :as routes]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]))

(ui/defview show
  {:route "/o/:org-id"}
  [params]
  (forms/with-form [_ ?filter]
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
       (header/entity org)
       [:div.p-body (field.ui/show-prose description)]
       [:div.p-body
        [:div.flex.gap-4.items-stretch
         [form.ui/filter-field ?filter {:loading? (:loading? result)}]
         [:a.btn.btn-white.flex.items-center.px-3
          {:href (routes/path-for ['sb.app.board-data/new
                                   {:query-params {:org-id (:entity/id org)}}])}
          (tr :tr/new-board)]]
        [ui/error-view result]
        (if (seq q)
          (for [[kind results] (dissoc (:value result) :q)
                :when (seq results)]
            [:<>
             [title (tr (keyword "tr" (name kind)))]
             [:div.grid.grid-cols-1.sm:grid-cols-2.md:grid-cols-3.lg:grid-cols-4.gap-2
              (map entity.ui/row results)]])
          [:div.flex-v.gap-2
           [title (tr :tr/boards)]
           [:div.grid.grid-cols-1.sm:grid-cols-2.md:grid-cols-3.lg:grid-cols-4.gap-2
            (map entity.ui/row (:entity/_parent org))]])]])))

(ui/defview settings
  {:route "/o/:org-id/settings"}
  [{:as params :keys [org-id]}]
  (let [org (data/settings params)]
    [:<>
     (header/entity org)
     [:div {:class form.ui/form-classes}
      (entity.ui/use-persisted org :entity/title field.ui/text-field)
      (entity.ui/use-persisted org :entity/description field.ui/prose-field)
      (entity.ui/use-persisted org :entity/domain-name domain.ui/domain-field)
      ;; TODO - uploading an image does not work
      (entity.ui/use-persisted org :image/avatar field.ui/image-field {:label (tr :tr/image.logo)})

      ]]))

(ui/defview new
  {:route       "/new/o"
   :view/router :router/modal}
  [params]
  (forms/with-form [!org (u/prune
                           {:entity/title       ?title
                            :entity/domain-name ?domain})
                    :required [?title ?domain]]
    [:form
     {:class     form.ui/form-classes
      :on-submit (fn [e]
                   (.preventDefault e)
                   (ui/with-submission [result (data/new! {:org @!org})
                                        :form !org]
                                       (routes/nav! [`show {:org-id (:entity/id result)}])))}
     [:h2.text-2xl (tr :tr/new-org)]
     [field.ui/text-field ?title {:label (tr :tr/title)}]
     (domain.ui/domain-field ?domain nil)
     [form.ui/submit-form !org (tr :tr/create)]]))