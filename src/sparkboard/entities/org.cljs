(ns sparkboard.entities.org
  (:require [applied-science.js-interop :as j]
            [inside-out.forms :as forms]
            [sparkboard.entities.domain :as domain]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.views.ui :as ui]
            [sparkboard.websockets :as ws]))

(ui/defview list-view [params]
  (ui/with-form [?pattern (when ?filter (str "(?i)" ?filter))]
    [:<>
     [:div.entity-header
      [:h3.header-title :tr/orgs]
      [ui/filter-field ?filter]
      [:div.btn.btn-light {:on-click #(routes/set-path! :org/new)} :tr/new-org]]
     (into [:div.card-grid]
           (comp
            (ui/filtered ?pattern)
            (map ui/entity-card))
           (:data params))]))

(ui/defview read-view [params]
  (forms/with-form [_ ?q]
    (let [{:as   org
           :keys [entity/title
                  entity/description
                  image/logo
                  image/background]} (:data params)
          q (ui/use-debounced-value (u/guard @?q #(> (count %) 2)) 500)
          result (when q (ws/once [:org/search (assoc params :q q)]))]
      [:div
       [:div.entity-header
        {:style {:background-image (ui/css-url (ui/asset-src background :page))}}
        (when logo
          [:img.h-10.w-10
           {:src (ui/asset-src logo :logo)}]) 
        [:h3.header-title title]
        [:a.inline-flex.items-center {:class "hover:text-muted-foreground"
                                      :href  (routes/entity org :edit)}
         [ui/icon:settings]]
        #_[:div

           {:on-click #(when (js/window.confirm (str "Really delete organization "
                                                     title "?"))
                         (routes/POST :org/delete params))}
           ]
        [ui/filter-field ?q {:loading? (:loading? result)}]
        [:a.btn.btn-light {:href (routes/path-for :org/new-board params)} :tr/new-board]]
       
       [:div.p-body (ui/show-prose description)]
       [ui/error-view result]

       (for [[kind results] (dissoc (:value result) :q)
             :when          (seq results)]
         [:<>
          [:h3.px-body.font-bold.text-lg.pt-6 (tr (keyword "tr" (name kind)))]
          [:div.card-grid (map ui/entity-card results)]])])))

(def form-el :form.flex.flex-col.gap-3.p-6.max-w-lg.mx-auto.bg-background)
(def button-el :button.btn.btn-primary.px-6.py-3.self-start)

(comment 
  (let [METADATA {:entity/id "foo"}]
    (macroexpand 
     '(forms/with-form [!org {:entity/id ?id}
                        :init METADATA])))
  )

(ui/defview edit-view [{:as params org :data}]
  (forms/with-form [!org (u/keep-changes org 
                          {:entity/id          (:entity/id org)
                           :entity/title       (?title :label :tr/title)
                           :entity/description (?description :label :tr/description) 
                           :entity/domain      ?domain
                           :image/logo        (?logo :label :tr/image.logo) 
                           :image/background  (?background :label :tr/image.background)})
                    :validators {?domain [domain/domain-valid-string
                                          (domain/domain-availability-validator)]}
                    :init org]
    [form-el
     {:on-submit (fn [e]
                   (j/call e :preventDefault)
                   (ui/with-submission [result (routes/POST [:org/edit params] @!org)
                                        :form !org]
                     (routes/set-path! :org/read params)))}
     (ui/text-field ?title)
     (ui/prose-field ?description)
     (domain/show-domain-field ?domain)
     [:div.flex.gap-6
      (ui/image-field ?logo)
      (ui/image-field ?background)]
     [:pre (ui/pprinted (forms/messages !org :deep true))]  
     (ui/show-field-messages !org)
     "old:"
     [:pre (ui/pprinted org)]
     "new:"
     [:pre (ui/pprinted @!org)]

     [button-el {:type "submit"
                 :disabled (not (forms/submittable? !org))}
      :tr/save]]) )

(ui/defview new-view [params]
  ;; TODO
  ;; page layout (narrow, centered)
  ;; typography
  (forms/with-form [!org (u/prune
                          {:entity/title ?title
                           :entity/domain ?domain})
                    :required [?title ?domain]
                    :validators {?domain [domain/domain-valid-string
                                          (domain/domain-availability-validator)]}]
    [form-el
     {:on-submit (fn [e]
                   (j/call e :preventDefault)
                   (ui/with-submission [result (routes/POST :org/new @!org)
                                        :form !org]
                     (routes/set-path! :org/read {:org (:entity/id result)})))}
     [:h2.text-2xl :tr/new-org] 
     (ui/show-field ?title {:label :tr/title})
     (domain/show-domain-field ?domain)

     (ui/show-field-messages !org)

     [button-el {:type     "submit"
                 :disabled (not (forms/submittable? !org))}
      :tr/create]]))
