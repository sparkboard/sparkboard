(ns sparkboard.entities.board
  (:require [inside-out.forms :as forms]
            [promesa.core :as p]
            [sparkboard.entities.account :as account]
            [sparkboard.entities.domain :as domain]
            [sparkboard.entities.entity :as entity]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.views.ui :as ui]
            [sparkboard.websockets :as ws]
            [re-db.api :as db]
            [sparkboard.views.radix :as radix]
            [yawn.view :as v]
            [yawn.hooks :as h]))

(ui/defview new [{:as params :keys [route]}]
  (let [orgs (ws/use-query [:account/orgs {:account (db/get :env/account :entity/id)}])
        owners (->> (:value orgs)
                    (map #(select-keys % [:entity/id :entity/title :image/avatar]))
                    (cons (entity/account-as-entity (db/get :env/account))))]
    (forms/with-form [!board (u/prune
                               {:entity/title  ?title
                                :entity/domain ?domain
                                :board/owner   [:entity/id (uuid (?owner :init (or (some-> params :query-params :org)
                                                                                   (str (db/get :env/account :entity/id)))))]})
                      :required [?title ?domain]]
      [:form
       {:class     ui/form-classes
        :on-submit (fn [^js e]
                     (.preventDefault e)
                     (ui/with-submission [result (routes/POST :board/new @!board)
                                          :form !board]
                       (routes/set-path! :org/read {:org (:entity/id result)})))}
       [:h2.text-2xl (tr :tr/new-board)]

       [:div.flex.flex-col.gap-2
        [ui/input-label {} (tr :tr/owner)]
        (->> owners
             (map (fn [{:keys [entity/id entity/title image/avatar]}]
                    (v/x [radix/select-item {:value (str id)
                                             :text title
                                             :icon [:img.w-5.h-5.rounded-sm {:src (ui/asset-src avatar :avatar)}]}])))
             (apply radix/select-menu {:value           @?owner
                                       :on-value-change (partial reset! ?owner)}))]

       (ui/show-field ?title {:label (tr :tr/title)})
       (domain/show-domain-field ?domain)
       (ui/show-field-messages !board)
       [ui/submit-form !board (tr :tr/create)]])))

(ui/defview register [{:as params :keys [route]}]
  (ui/with-form [!member {:member/name ?name :member/password ?pass}]
    [:div
     [:h3 (tr :tr/register)]
     (ui/show-field ?name)
     (ui/show-field ?pass)
     [:button {:on-click #(p/let [res (routes/POST route @!member)]
                            ;; TODO - how to determine POST success?
                            #_(when (http-ok? res)
                                (routes/set-path! [:board/read params])
                                res))}
      (tr :tr/register)]]))

(ui/defview read [{:as params board :data}]
  (let [!tab (h/use-state (tr :tr/projects))
        ?filter (h/use-state nil)
        tabs [{:title   (tr :tr/projects)
               :content [entity/show-filtered-results {:results (:project/_board board)}]}
              {:title   (tr :tr/members)
               :content [entity/show-filtered-results {:results (->> (:member/_entity board) (map #(merge (:member/account %) %)))}]}]]
    [:<>
     [:h1 (:entity/title board)]
     [:p (-> board :entity/domain :domain/name)]

     ;; TODO new project
     #_[:a {:href (routes/path-for :project/new params)} (tr :tr/new-project)]
     [:blockquote
      [ui/safe-html (-> board
                        :entity/description
                        :prose/string)]]

     [radix/tab-root {:value           @!tab
                      :on-value-change (partial reset! !tab)}
      ;; tabs
      [:div.mt-6.flex.items-stretch.px-body.h-10.gap-3
       [radix/show-tab-list tabs]
       [:div.flex-grow]
       [ui/filter-field ?filter]]

      (for [{:keys [title content]} tabs]
        [radix/tab-content {:value title} content])]



     ]))

(comment
  [:ul                                                 ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])