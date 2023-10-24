(ns sparkboard.app.board
  (:require #?(:clj [sparkboard.server.datalevin :as dl])
            #?(:cljs [sparkboard.ui.radix :as radix])
            #?(:cljs [yawn.hooks :as h])
            [sparkboard.authorize :as az]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [sparkboard.app.member :as member]
            [sparkboard.validate :as validate]
            [sparkboard.app.domain :as domain]
            [sparkboard.entity :as entity]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.ui.header :as header]
            [sparkboard.ui.icons :as icons]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.ui :as ui]
            [sparkboard.query :as query]
            [re-db.api :as db]
            [yawn.view :as v]
            [sparkboard.schema :as sch :refer [s- ?]]))

(sch/register!
  {:board/show-project-numbers?              {s-   :boolean
                                              :doc "Show 'project numbers' for this board"}
   :board/max-members-per-project            {:doc "Set a maximum number of members a project may have"
                                              s-   :int}
   :board/project-sharing-buttons            {:doc "Which social sharing buttons to display on project detail pages",
                                              s-   [:map-of :social/sharing-button :boolean]}
   :board/is-template?                       {:doc "Board is only used as a template for creating other boards",
                                              s-   :boolean},
   :board/labels                             {:unsure "How can this be handled w.r.t. locale?"
                                              s-      [:map-of [:enum
                                                                :label/member.one
                                                                :label/member.many
                                                                :label/project.one
                                                                :label/project.many] :string]},
   :board/owner                              (sch/ref :one)
   :board/instructions                       {:doc "Secondary instructions for a board, displayed above projects"
                                              s-   :prose/as-map},
   :board/max-projects-per-member            {:doc "Set a maximum number of projects a member may join"
                                              s-   :int}
   :board/sticky-color                       {:doc "Border color for sticky projects"
                                              s-   :html/color}
   :board/member-tags                        (sch/ref :many :tag/as-map)
   :board/project-fields                     (sch/ref :many :field/as-map)
   :board/member-fields                      (sch/ref :many :field/as-map)
   :board/registration-invitation-email-text {:doc "Body of email sent when inviting a user to a board."
                                              s-   :string},
   :board/registration-newsletter-field?     {:doc "During registration, request permission to send the user an email newsletter"
                                              s-   :boolean},
   :board/registration-open?                 {:doc "Allows new registrations via the registration page. Does not affect invitations.",
                                              s-   :boolean},
   :board/registration-message               {:doc "Content displayed on registration screen (before user chooses provider / enters email)"
                                              s-   :prose/as-map},
   :board/registration-url-override          {:doc "URL to redirect user for registration (replaces the Sparkboard registration page, admins are expected to invite users)",
                                              s-   :http/url},
   :board/registration-codes                 {s- [:map-of :string [:map {:closed true} [:registration-code/active? :boolean]]]}
   :board/new-projects-require-approval?     {s- :boolean}
   :board/custom-css                         {:doc "Custom CSS for this board"
                                              s-   :string}
   :board/custom-js                          {:doc "Custom JS for this board"
                                              s-   :string}
   :board/as-map                             {s- [:map {:closed true}
                                                  :entity/id
                                                  :entity/title
                                                  :entity/created-at
                                                  :entity/public?
                                                  :entity/kind

                                                  :board/owner
                                                  :board/registration-open?

                                                  (? :image/avatar)
                                                  (? :image/logo-large)
                                                  (? :image/footer)
                                                  (? :image/background)
                                                  (? :image/sub-header)

                                                  (? :entity/website)
                                                  (? :entity/meta-description)
                                                  (? :entity/description)
                                                  (? :entity/domain)
                                                  (? :entity/locale-default)
                                                  (? :entity/locale-dicts)
                                                  (? :entity/locale-suggestions)
                                                  (? :entity/social-feed)
                                                  (? :entity/deleted-at)
                                                  (? :entity/created-by)

                                                  (? :board/custom-css)
                                                  (? :board/custom-js)
                                                  (? :board/instructions)
                                                  (? :board/is-template?)
                                                  (? :board/labels)
                                                  (? :board/max-members-per-project)
                                                  (? :board/max-projects-per-member)
                                                  (? :board/member-fields)
                                                  (? :board/member-tags)
                                                  (? :board/new-projects-require-approval?)
                                                  (? :board/project-fields)
                                                  (? :board/project-sharing-buttons)
                                                  (? :board/registration-codes)
                                                  (? :board/registration-invitation-email-text)
                                                  (? :board/registration-message)
                                                  (? :board/registration-newsletter-field?)
                                                  (? :board/registration-url-override)
                                                  (? :board/show-project-numbers?)
                                                  (? :board/slack.team)
                                                  (? :board/sticky-color)

                                                  (? :member-vote/open?)
                                                  (? :webhook/subscriptions)]}})
#?(:clj
   (defn board:register!
     {:endpoint {:post ["/b/" ['entity/id :board-id] "/register"]}}
     [req {:as params registration-data :body}]
     ;; create membership
     ))

(query/defquery db:read
  {:prepare [az/with-account-id
             (member/member:log-visit! :board-id)]}
  [{:keys [board-id]}]
  (db/pull `[~@entity/fields
             :board/registration-open?
             {:board/owner [~@entity/fields :org/show-org-tab?]}
             {:project/_board [~@entity/fields :entity/archived?]}
             {:member/_entity [~@entity/fields {:member/account ~entity/account-as-entity-fields}]}]
           board-id))
(comment

  (db/pull
    `[:entity/id]
    [:entity/id #uuid"a12f4a7f-7bfb-3b83-9a29-df208ec981f1"])

  (db/pull `[:entity/id
             {:project/_board [~@entity/fields]}]
           [:entity/id #uuid"a12f4a7f-7bfb-3b83-9a29-df208ec981f1"])
  (db:read {:board-id ex-board-id}))



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
                                          (when-let [existing-board (dl/entity (:entity/id board))]
                                            (entity/can-edit? (:db/id existing-board) account-id))
                                          ;; account is admin of board's org
                                          (entity/can-edit? owner-id account-id))))]]])))

#?(:clj
   (defn db:edit!
     {:endpoint {:post ["/b/" ['entity/id :board-id] "/edit"]}}
     [{:keys [account]} {:keys [board-id] board :body}]
     (let [board (entity/conform (assoc board :entity/id board-id) :board/as-map)]
       (db:authorize-edit! board (:db/id account))
       (db/transact! [board])
       {:body board})))

#?(:clj
   (defn db:edit
     {:endpoint {:query true}
      :prepare  [az/with-account-id!
                 (fn [_ {:as params :keys [board-id account-id]}]
                   (db:authorize-edit! (dl/entity board-id)
                                       account-id)
                   params)]}
     [{:keys [board-id]}]
     (db/pull (u/template
                [*
                 ~@entity/fields])
              board-id)))

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
  {:route       ["/b/" "new"]
   :view/target :modal}
  [{:as params :keys [route]}]
  (let [owners (->> (query/use! ['sparkboard.app.account/db:account-orgs])
                    (cons (entity/account-as-entity (db/get :env/config :account))))]
    (forms/with-form [!board (u/prune
                               {:entity/title  ?title
                                :entity/domain ?domain
                                :board/owner   [:entity/id (uuid (?owner :init (or (-> params :query-params :org)
                                                                                   (str (-> (db/get :env/config :account)
                                                                                            :entity/id)))))]})
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
  {:route ["/b/" ['entity/id :board-id] "/register"]}
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
  (let [board (query/use! [`db:read params])]
    [:div.p-body (ui/show-prose (:entity/description board))])
  )

(ui/defview read:signed-in
  [params]
  (let [board       (db:read params)
        current-tab (:board/tab params "projects")
        ?filter     (h/use-state nil)
        tabs        [["projects" (tr :tr/projects) (db/where [[:project/board (:db/id board)]])]
                     ["members" (tr :tr/members) (->> (:member/_entity board) (map #(merge (:member/account %) %)))]]]
    [:<>
     [header/entity board
      [header/btn {:icon [icons/settings]
                   :href (routes/path-for 'sparkboard.app.board/edit params)}]
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

(ui/defview show
  {:route ["/b/" ['entity/id :board-id]]}
  [params]
  (if (db/get :env/config :account)
    [read:signed-in params]
    [read:public params]))

(ui/defview edit [params]
  (let [board (query/use! [`db:edit params])]
    [:pre (ui/pprinted board)]))

(ui/defview read:tab
  {:route ["/b/" ['entity/id :board-id] "/" :board/tab]}
  [params]
  (case (:board/tab params)
    "settings"
    (edit params)
    (show params)))

(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])