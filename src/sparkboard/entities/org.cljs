(ns sparkboard.entities.org
  (:require [applied-science.js-interop :as j]
            [inside-out.forms :as forms]
            [re-db.reactive :as r]
            [sparkboard.client.views :as views]
            [sparkboard.entities.domain :as domain]
            [sparkboard.entities.entity :as entity]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.views.ui :as ui]
            [sparkboard.websockets :as ws]))

(ui/defview list-view [params]
  (ui/with-form [?pattern (when ?filter (str "(?i)" ?filter))]
    [:<>
     [:div.entity-header 
      [:h3 :tr/orgs]
      [ui/filter-field ?filter]
      [:div.btn.btn-light {:on-click #(routes/set-path! :org/new)} :tr/new-org]]
     (into [:div.card-grid]
           (comp
            (ui/filtered ?pattern)
            (map entity/card))
           (:data params))]))

(defn entity-header [{:as   entity
                      :keys [entity/title
                             entity/description
                             image/logo
                             image/background]} & children]
  (into [:div.entity-header
         {:style {:background-image (ui/css-url (ui/asset-src background :page))}}
         (when logo
           [:a.contents {:href (routes/entity entity :read)}
            [:img.h-10.w-10
             {:src (ui/asset-src logo :logo)}]])
         [:a.contents {:href (routes/entity entity :read)} [:h3 title]]] 
        (concat children [[views/header:account]])))

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
       
       (entity-header org 
                      [:a.inline-flex.items-center {:class "hover:text-txt/60"
                                                    :href  (entity/route org :org/edit)}
                       [ui/icon:settings]]
                      #_[:div

                         {:on-click #(when (js/window.confirm (str "Really delete organization "
                                                                   title "?"))
                                       (routes/POST :org/delete params))}]
                      [ui/filter-field ?q {:loading? (:loading? result)}]
                      [:a.btn.btn-light {:href (entity/route org :org/new-board)} :tr/new-board])
       
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
               :when          (seq results)]
           [:<>
            [:h3.px-body.font-bold.text-lg.pt-6 (tr (keyword "tr" (name kind)))]
            [:div.card-grid (map entity/card results)]])
         [:div.card-grid (map entity/card (:board/_org org))])])))

(def form-classes "flex flex-col gap-8 p-6 max-w-lg mx-auto bg-back relative")
(def button-el :button.btn.btn-primary.px-6.py-3.self-start)

(ui/defview edit-view [{:as params org :data}]
  (prn (:entity/title org))
  (forms/with-form [!org (u/keep-changes org 
                                         {:entity/id          (:entity/id org)
                                          :entity/title       (?title :label :tr/title)
                                          :entity/description (?description :label :tr/description) 
                                          :entity/domain      ?domain
                                          :image/logo        (?logo :label :tr/image.logo) 
                                          :image/background  (?background :label :tr/image.background)})
                    :validators {?domain [domain/domain-valid-string
                                          (domain/domain-availability-validator)]}
                    :init org
                    :form/auto-submit #(routes/POST [:org/edit params] %)]
    [:<> 
     (entity-header org)
     
     [:div {:class form-classes}
      
      (ui/show-field-messages !org)
      (ui/text-field ?title)
      (ui/prose-field ?description)
      (domain/show-domain-field ?domain)
      [:div.flex.flex-col.gap-2
       [ui/input-label {} :tr/images ]
       [:div.flex.gap-6
        (ui/image-field ?logo)
        (ui/image-field ?background)]]
      [:a.btn.btn-primary.p-4 {:href (routes/entity org :read)} :tr/done]]]))

(ui/defview new-view [params]
  (forms/with-form [!org (u/prune
                          {:entity/title ?title
                           :entity/domain ?domain})
                    :required [?title ?domain]
                    :validators {?domain [domain/domain-valid-string
                                          (domain/domain-availability-validator)]}]
    [:form
     {:class form-classes
      :on-submit (fn [e]
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

(comment 
  (r/session 
   (forms/with-form [!a {:b ?b}
                     :required [?b]
                     :meta {:x :foo
                            ?b {:abra :cadabra}}
                     
                     ]
     (forms/closest ?b :x)
     (meta !a)
     (:required ?b)
     (:x !a)
     (meta ?b))))