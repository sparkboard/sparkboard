(ns sparkboard.views.board
  (:require #?(:clj [sparkboard.datalevin :as dl])
            #?(:cljs [sparkboard.views.radix :as radix])
            #?(:cljs [yawn.hooks :as h])
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [sparkboard.validate :as validate]
            [sparkboard.domains :as domain]
            [sparkboard.entity :as entity]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.icons :as icons]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.views.ui :as ui]
            [sparkboard.websockets :as ws]
            [re-db.api :as db]
            [yawn.view :as v]
            [sparkboard.endpoints #?(:cljs :as-alias :clj :as) endpoints]))

#?(:clj
   (defn board:register!
     {:endpoint {:post ["/b/" ['uuid :board-id] "/register"]}}
     [req {:as params registration-data :body}]
     ;; create membership
     ))

#?(:clj
   (defn db:read
     {:endpoint  {:query ["/b/" ['uuid :board-id]]}
      :authorize (fn [req params]
                   (endpoints/member:read-and-log! (:board-id params) (:db/id (:account req)))
                   params)}
     [{:keys [board-id]}]
     (db/pull `[~@entity/fields
                :board/registration-open?
                {:board/owner [~@entity/fields :org/show-org-tab?]}
                {:project/_board [~@entity/fields :* :entity/archived?]}
                {:member/_entity [~@entity/fields {:member/account ~entity/account-as-entity-fields}]}]
              [:entity/id board-id])))



#?(:clj
   (defn db:authorize-edit! [board account-id]
     (validate/assert board [:map [:board/owner
                                   [:fn {:error/message "Not authorized."}
                                    (fn [owner]
                                      (let [owner-id (:db/id (dl/entity owner))]
                                        (or
                                          ;; board is owned by this account
                                          (= account-id owner-id)
                                          ;; account is editor of this board (existing)
                                          (when-let [existing-board (dl/entity [:entity/id (:entity/id board)])]
                                            (entity/can-edit? (:db/id existing-board) account-id))
                                          ;; account is admin of board's org
                                          (entity/can-edit? owner-id account-id))))]]])))

#?(:clj
   (defn db:edit!
     {:endpoint {:post ["/b/" ['uuid :board-id] "/edit"]}}
     [{:keys [account]} {:keys [board-id] board :body}]
     (let [board (entity/conform (assoc board :entity/id board-id) :board/as-map)]
       (db:authorize-edit! board (:db/id account))
       (db/transact! [board])
       {:body board})))

#?(:clj
   (defn db:edit
     {:endpoint  {:query ["/b/" ['uuid :board-id] "/edit"]}
      :authorize (fn [req {:as params :keys [board-id]}]
                   (db:authorize-edit! (dl/entity [:entity/id board-id])
                                       (:db/id (:account req)))
                   params)}
     [{:keys [board-id]}]
     (db/pull (u/template
                [*
                 ~@entity/fields])
              [:entity/id board-id])))

#?(:clj
   (defn db:new!
     {:endpoint {:post ["/b/" "new"]}}
     [{:keys [account]} {board :body}]
     ;; TODO
     ;; confirm that owner is account, or account is admin of org
     (let [board  (-> (dl/new-entity board :board :by (:db/id account))
                      (entity/conform :board/as-map))
           _      (db:authorize-edit! board (:db/id account))
           member (-> {:member/entity  board
                       :member/account (:db/id account)
                       :member/roles   #{:role/admin}}
                      (dl/new-entity :member))]
       (db/transact! [member])
       {:body board})))

(ui/defview new
  {:endpoint    {:view ["/b/" "new"]}
   :view/target :modal}
  [{:as params :keys [route]}]
  (let [owners (->> (ws/use-query! ['sparkboard.views.account/account:orgs])
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
                     (ui/with-submission [result (routes/POST `db:new! @!board)
                                          :form !board]
                       (routes/set-path! :org/read {:org-id (:entity/id result)})))}
       [:h2.text-2xl (tr :tr/new-board)]

       [:div.flex.flex-col.gap-2
        [ui/input-label {} (tr :tr/owner)]
        (->> owners
             (map (fn [{:keys [entity/id entity/title image/avatar]}]
                    (v/x [radix/select-item {:value (str id)
                                             :text  title
                                             :icon  [:img.w-5.h-5.rounded-sm {:src (ui/asset-src avatar :avatar)}]}])))
             (apply radix/select-menu {:value           @?owner
                                       :on-value-change (partial reset! ?owner)}))]

       (ui/show-field ?title {:label (tr :tr/title)})
       (domain/show-domain-field ?domain)
       (ui/show-field-messages !board)
       [ui/submit-form !board (tr :tr/create)]])))

(ui/defview register
  {:endpoint {:view ["/b/" ['uuid :board-id] "/register"]}}
  [{:as params :keys [route]}]
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

(ui/defview read:public [params]
  (let [board (ws/use-query! [`db:read params])]
    [:div.p-body (ui/show-prose (:entity/description board))])
  )

(ui/defview read:signed-in
  [params]
  (let [board       (ws/use-query! [`db:read params])
        current-tab (:board/tab params "projects")
        ?filter     (h/use-state nil)
        tabs        [["projects" (tr :tr/projects) (:project/_board board)]
                     ["members" (tr :tr/members) (->> (:member/_entity board) (map #(merge (:member/account %) %)))]]]
    [:<>
     [ui/entity-header board
      [ui/header-btn [icons/settings]
       (routes/path-for 'sparkboard.views.board/edit params)]
      ]

     ;; TODO new project
     #_[:a {:href (routes/path-for :project/new params)} (tr :tr/new-project)]


     [radix/tab-root {:value           current-tab
                      :on-value-change #(routes/set-path!
                                          `read:tab (assoc params :board/tab %))}
      ;; tabs
      [:div.mt-6.flex.items-stretch.px-body.h-10.gap-3
       [radix/show-tab-list (for [[value title _] tabs] {:title title :value value})]
       [:div.flex-grow]
       [ui/filter-field ?filter]]

      (for [[value title entities] tabs]
        [radix/tab-content {:value value}
         [entity/show-filtered-results {:results entities}]])]]))

(ui/defview read
  {:endpoint {:view ["/b/" ['uuid :board-id]]}}
  [params]
  (if (db/get :env/account)
    [read:signed-in params]
    [read:public params]))

(ui/defview edit [params]
  (let [board (ws/use-query! [`db:edit params])]
    [:pre (ui/pprinted board)]))

(ui/defview read:tab
  {:endpoint {:view ["/b/" ['uuid :board-id] "/" :board/tab]}}
  [params]
  (case (:board/tab params)
    "settings"
    (edit params)
    (read params)))

(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])