(ns sparkboard.entities.org
  (:require [applied-science.js-interop :as j]
            [inside-out.forms :as forms]
            [sparkboard.entities.account :as account]
            [sparkboard.entities.domain :as domain]
            [sparkboard.entities.entity :as entity]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.icons :as icons]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.views.ui :as ui]
            [sparkboard.websockets :as ws]
            [re-db.api :as db]))

(defn entity-header [{:as   entity
                      :keys [entity/title
                             image/avatar]} & children]
  (into [:div.entity-header

         (when avatar
           [:a.contents {:href (routes/entity entity :read)}
            [:img.h-10.w-10
             {:src (ui/asset-src avatar :avatar)}]])
         [:a.contents {:href (routes/entity entity :read)} [:h3 title]]]
        (concat children [[account/header:account]])))

(ui/defview read [params]
  (forms/with-form [_ ?q]
    (let [{:as   org
           :keys [entity/description]} (:data params)
          q      (ui/use-debounced-value (u/guard @?q #(> (count %) 2)) 500)
          result (ws/use-query [:org/search {:org (:org params)
                                             :q q}])]
      [:div
       (entity-header org
                      [:a.inline-flex.items-center {:class "hover:text-txt/60"
                                                    :href  (entity/href org :org/edit)}
                       [icons/settings]]
                      #_[:div

                         {:on-click #(when (js/window.confirm (str "Really delete organization "
                                                                   title "?"))
                                       (routes/POST :org/delete params))}]
                      [ui/filter-field ?q {:loading? (:loading? result)}]
                      [:a.btn.btn-light {:href (routes/href :board/new
                                                            {:query-params {:org (:entity/id org)}})} (tr :tr/new-board)])

       [:div.p-body.whitespace-pre
        "This is the landing page for an organization. Its purpose is to provide a quick overview of the organization and list its boards.
         - show hackathons by default. sort-by date, group-by year.
         - tabs: hackathons, projects, [about / external tab(s)]
         - search
         
         "
        ]
       [:div.p-body (ui/show-prose description)]
       [ui/error-view result]
       (if (seq q)
         (for [[kind results] (dissoc (:value result) :q)
               :when (seq results)]
           [:<>
            [:h3.px-body.font-bold.text-lg.pt-6 (tr (keyword "tr" (name kind)))]
            [:div.card-grid (map entity/card results)]])
         [:div.card-grid (map entity/card (:board/_owner org))])])))

(ui/defview edit [{:as params org :data}]
  (forms/with-form [!org (u/keep-changes org
                                         {:entity/id          (:entity/id org)
                                          :entity/title       (?title :label (tr :tr/title))
                                          :entity/description (?description :label (tr :tr/description))
                                          :entity/domain      ?domain
                                          :image/avatar       (?logo :label (tr :tr/image.logo))
                                          :image/background   (?background :label (tr :tr/image.background))})
                    :validators {?domain [domain/domain-valid-string
                                          (domain/domain-availability-validator)]}
                    :init org
                    :form/auto-submit #(routes/POST [:org/edit params] %)]
    [:<>
     (entity-header org)

     [:div {:class ui/form-classes}

      (ui/show-field-messages !org)
      (ui/text-field ?title)
      (ui/prose-field ?description)
      (domain/show-domain-field ?domain)
      [:div.flex.flex-col.gap-2
       [ui/input-label {} (tr :tr/images)]
       [:div.flex.gap-6
        (ui/image-field ?logo)
        (ui/image-field ?background)]]
      [:a.btn.btn-primary.p-4 {:href (routes/entity org :read)} (tr :tr/done)]]]))

(ui/defview new [params]
  (forms/with-form [!org (u/prune
                           {:entity/title  ?title
                            :entity/domain ?domain})
                    :required [?title ?domain]]
    [:form
     {:class     ui/form-classes
      :on-submit (fn [e]
                   (.preventDefault e)
                   (ui/with-submission [result (routes/POST [:org/new params] @!org)
                                        :form !org]
                     (routes/set-path! :org/read {:org (:entity/id result)})))}
     [:h2.text-2xl (tr :tr/new-org)]
     (ui/show-field ?title {:label (tr :tr/title)})
     (domain/show-domain-field ?domain)
     (ui/show-field-messages !org)
     [ui/submit-form !org (tr :tr/create)]]))