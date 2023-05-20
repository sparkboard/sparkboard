(ns sparkboard.entities.org
  (:require #?(:clj [datalevin.core :as dl])
            #?(:clj [sparkboard.datalevin :as sd])
            [sparkboard.server.query :as query]
            [applied-science.js-interop :as j]
            [bidi.bidi :as bidi]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.entities.domain :as domain]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.validate :as validate]
            [sparkboard.views.ui :as ui]
            [sparkboard.websockets :as ws]
            [sparkboard.util :as u]))

#?(:clj
   (defn new$post
     [{:keys [account]} _ org]
     (let [org (update-in org [:entity/domain :domain/name] #(some-> % domain/qualify-domain))
           _ (validate/assert org [:map {:closed true}
                                   :entity/title
                                   :entity/description
                                   [:entity/domain [:map {:closed true}
                                                    [:domain/name [:re #"^[a-z0-9-.]+.sparkboard.com$"]]]]])
           org (sd/new-entity org
                              :by (:db/id account)
                              :legacy-id :entity/id)]
       (db/transact! [org])
       {:body org})))

(defn delete:post
  "Mutation fn. Retracts organization by given org-id."
  [_req {:keys [org]}]
  ;; auth: user is admin of org
  ;; todo: retract org and all its boards, projects, etc.?
  (db/transact! [[:db.fn/retractEntity [:entity/id org]]])
  {:body ""})


(query/reactive $settings:query [params]
                ;; all the settings that can be changed
                (db/pull '[*
                           {:entity/domain [:domain/name]}] [:entity/id (:org params)]))

(defn settings:post [{:keys [account]} {:keys [org]} _payload]
  ;; merge payload with org, validate, transact
  )

(query/reactive $index$query [_]
                (->> (db/where [[:entity/kind :org]])
                     (mapv (re-db.api/pull '[*]))))

(query/reactive $read:query [params]
                (db/pull '[:entity/id
                           :entity/kind
                           :entity/title
                           {:board/_org [:entity/created-at
                                         :entity/id
                                         :entity/kind
                                         :entity/title]}
                           {:entity/domain [:domain/name]}]
                         [:entity/id (:org params)]))

#?(:clj
   (defn search:query [{:keys [org q]}]
     {:q q
      :boards (dl/q '[:find [(pull ?board [:entity/id
                                           :entity/title
                                           :entity/kind
                                           :entity/images
                                           {:entity/domain [:domain/name]}]) ...]
                      :in $ ?terms ?org
                      :where
                      [?board :board/org ?org]
                      [(fulltext $ ?terms {:top 100}) [[?board ?a ?v]]]]
                    @sd/conn
                    q
                    [:entity/id org])
      :projects (dl/q '[:find [(pull ?project [:entity/id
                                               :entity/title
                                               :entity/kind
                                               :entity/description
                                               :entity/images
                                               {:project/board [:entity/id]}]) ...]
                        :in $ ?terms ?org
                        :where
                        [?board :board/org ?org]
                        [?project :project/board ?board]
                        [(fulltext $ ?terms {:top 100}) [[?project ?a ?v]]]]
                      @sd/conn
                      q
                      [:entity/id org])}))

(ui/defview index$view [params]
  (ui/with-form [?pattern (str "(?i)" ?filter)]
    [:<>
     [:div.entity-header
      [:h3.header-title :tr/orgs]
      [ui/filter-input ?filter]
      [:div.btn.btn-light {:on-click #(routes/set-path! :org/new)} :tr/new-org]]
     (into [:div.card-grid]
           (comp
             (filter (if @?filter
                       #(re-find (re-pattern @?pattern) (:entity/title %))
                       identity))
             (map ui/entity-card))
           (:data params))]))

(ui/defview read:view [params]
  (forms/with-form [_ ?q]
    (let [{:as org :keys [entity/title]} (:value @(ws/$query [:org/read params]))
          q (ui/use-debounced-value (u/guard @?q #(> (count %) 2)) 500)
          result (when q @(ws/$query [:org/search (assoc params :q q)]))]
      [:div
       [:div.entity-header
        [:h3.header-title title]
        [:a.inline-flex.items-center {:class "hover:text-muted-foreground"
                                      :href (routes/entity org :settings)}
         [ui/icon:settings]]
        #_[:div

           {:on-click #(when (js/window.confirm (str "Really delete organization "
                                                     title "?"))
                         (routes/POST :org/delete params))}
           ]
        [ui/filter-input ?q {:loading? (:loading? result)}]
        [:a.btn.btn-light {:href (routes/path-for :org/new-board params)} :tr/new-board]]
       [ui/error-view result]

       (for [[kind results] (dissoc (:value result) :q)
             :when (seq results)]
         [:<>
          [:h3.px-body.font-bold.text-lg.pt-6 (tr (keyword "tr" (name kind)))]
          [:div.card-grid (map ui/entity-card results)]])])))

(ui/defview settings:view [params]

  )

(ui/defview new$view [params]
  ;; TODO
  ;; page layout (narrow, centered)
  ;; typography
  (forms/with-form [!org {:entity/title ?title
                          :entity/description ?description
                          :org/public? ?public
                          :entity/domain {:domain/name ?domain}}
                    :required [?title ?domain]
                    :validators {?public [(fn [v _]
                                            (when-not v
                                              (forms/message :invalid "Too bad")))]
                                 ?domain [(forms/min-length 3)
                                          domain/domain-valid-chars
                                          (domain/domain-availability-validator)]}]
    [:form.flex.flex-col.gap-3.p-6.max-w-lg.mx-auto.bg-background
     {:on-submit (fn [e]
                   (j/call e :preventDefault)
                   (forms/try-submit+ !org
                     (p/let [result (routes/POST :org/new @!org)]
                       (when-not (:error result)
                         (routes/set-path! :org/read {:org (:entity/id result)}))
                       result)))}

     [:h2.text-2xl :tr/new-org]
     (ui/show-field ?title {:label :tr/title})
     (ui/show-field ?domain {:label :tr/domain-name
                             :auto-complete "off"
                             :spell-check false
                             :placeholder "XYZ.sparkboard.com"
                             :postfix (when @?domain [:span.text-sm.text-gray-500 ".sparkboard.com"])})
     (ui/show-field ?description {:label :tr/description})

     (into [:<>] (map ui/view-message (forms/visible-messages !org)))

     [:button.btn.btn-primary.px-6.py-3.self-start {:type "submit"
                                                    :disabled (not (forms/submittable? !org))}
      :tr/create]]))