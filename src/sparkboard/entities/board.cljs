(ns sparkboard.entities.board
  (:require [inside-out.forms :as forms]
            [promesa.core :as p]
            [sparkboard.entities.account :as account]
            [sparkboard.entities.domain :as domain]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.views.ui :as ui]
            [sparkboard.websockets :as ws]
            [re-db.api :as db]
            [sparkboard.views.radix :as radix]
            [yawn.view :as v]))

(ui/defview new [{:as params :keys [route]}]
  (let [orgs (ws/use-query [:account/orgs params])
        owners (->> (:value orgs)
                    (map #(select-keys % [:entity/id :entity/title :image/logo]))
                    (cons (let [{:keys [entity/id account/display-name account/photo]} (db/get :env/account)]
                            {:entity/id    id
                             :entity/title (tr :tr/personal-account [display-name])
                             :image/logo   photo})))]
    (forms/with-form [!board (u/prune
                               {:entity/title  ?title
                                :entity/domain ?domain
                                :board/owner   (uuid (?owner :init (or (some-> params :query-params :org)
                                                                       (str (db/get :env/account :entity/id)))))})
                      :required [?title ?domain]]
      [:<>
       (account/header params)
       [:form
        {:class     ui/form-classes
         :on-submit (fn [^js e]
                      (.preventDefault e)
                      (ui/with-submission [result (routes/POST [:board/new params] @!board)
                                           :form !board]
                        (routes/set-path! :org/read {:org (:entity/id result)})))}
        [:h2.text-2xl (tr :tr/new-board)]

        [:div.flex.flex-col.gap-2
         [ui/input-label {} (tr :tr/owner)]
         (->> owners
              (map (fn [{:keys [entity/id entity/title image/logo]}]
                     (v/x [radix/select-item (str id) [:div.flex.gap-2
                                                       [:img.w-5.h-5.rounded-sm {:src (ui/asset-src logo :logo)}]
                                                       title]])))
              (apply radix/select-menu {:value           @?owner
                                        :on-value-change #(reset! ?owner %)}))]

        (ui/show-field ?title {:label (tr :tr/title)})
        (domain/show-domain-field ?domain)
        (ui/show-field-messages !board)
        [ui/submit-form !board (tr :tr/create)]]])))

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
  [:<>
   [:h1 (:entity/title board)]
   [:p (-> board :entity/domain :domain/name)]
   [:blockquote
    [ui/safe-html (-> board
                      :entity/description
                      :prose/string)]]
   ;; TODO - tabs
   [:div.rough-tabs {:class "w-100"}
    [:div.rough-tab                                         ;; projects
     [:a {:href (routes/path-for :project/new params)} (tr :tr/new-project)]
     (into [:ul]
           (map (fn [proj]
                  [:li [:a {:href (routes/path-for :project/read {:project (:entity/id proj)})}
                        (:entity/title proj)]]))
           (:project/_board board))]
    [:div.rough-tab                                         ;; members
     [:a {:href (routes/path-for :board/register params)} (tr :tr/new-member)]
     (into [:ul]
           (map (fn [member]
                  [:li
                   [:a {:href (routes/path-for :member/read {:member (:entity/id member)})}
                    (:member/name member)]]))
           (:member/_board board))]
    [:div.rough-tab {:name  "I18n"                          ;; FIXME any spaces in the tab name cause content to break; I suspect a bug in `with-props`. DAL 2023-01-25
                     :class "db"}
     [:ul                                                   ;; i18n stuff
      [:li "suggested locales:" (str (:entity/locale-suggestions board))]
      [:li "default locale:" (str (:i18n/default-locale board))]
      [:li "extra-translations:" (str (:i18n/locale-dicts board))]]]]])
