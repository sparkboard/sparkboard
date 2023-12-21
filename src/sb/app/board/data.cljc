(ns sb.app.board.data
  (:require [re-db.api :as db]
            [sb.app.field.data :as field.data]
            [sb.app.entity.data :as entity.data]
            [sb.app.member.data :as member.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.validate :as validate]))

(sch/register!
  {:board/project-numbers?               {s-    :boolean
                                          :hint "Assign numbers to this board's projects."}
   :board/max-members-per-project        {s- :int}
   :board/project-sharing-buttons        {:hint "Social sharing buttons to be displayed on project detail pages"
                                          s-    [:map-of :social/sharing-button :boolean]}
   :board/is-template?                   {:doc "Board is only used as a template for creating other boards"
                                          s-   :boolean},
   :board/labels                         {:unsure "How can this be handled w.r.t. locale?"
                                          s-      [:map-of [:enum
                                                            :label/member.one
                                                            :label/member.many
                                                            :label/project.one
                                                            :label/project.many] :string]},
   :board/home-page-message              {:hint "Additional instructions for a board, displayed when a member has signed in."
                                          s-    :prose/as-map},
   :board/max-projects-per-member        {s- :int}
   :board/sticky-color                   {:doc "Deprecate - sticky notes can pick their own colors"
                                          s-   :html/color}
   :board/member-tags                    {s- [:sequential :tag/as-map]}
   :board/project-fields                 {s- [:sequential :field/as-map]}
   :board/member-fields                  {s- [:sequential :field/as-map]}
   :board/invite-email-text              {:hint "Text of email sent when inviting a user to a board."
                                          s-    :string},
   :board/registration-newsletter-field? {:hint "During registration, request permission to send the user an email newsletter"
                                          s-    :boolean},
   :board/registration-open?             {:hint "Allows new registrations via the registration page. Does not affect invitations."
                                          s-    :boolean},
   :board/registration-page-message      {:hint "Content displayed on registration screen (before user chooses provider / enters email)"
                                          s-    :prose/as-map},
   :board/registration-url-override      {:hint "URL to redirect user for registration (replaces the Sparkboard registration page, admins are expected to invite users)",
                                          s-    :http/url},
   :board/registration-codes             {s- [:map-of :string [:map {:closed true} [:registration-code/active? :boolean]]]}
   :board/new-projects-require-approval? {s- :boolean}
   :board/custom-css                     {s- :string}
   :board/custom-js                      {s- :string}
   :board/as-map                         {s- [:map {:closed true}
                                              :entity/id
                                              :entity/title
                                              :entity/created-at
                                              :entity/public?
                                              :entity/kind
                                              :entity/parent

                                              :board/registration-open?

                                              (? :image/avatar)
                                              (? :image/logo-large)
                                              (? :image/footer)
                                              (? :image/background)
                                              (? :image/sub-header)

                                              (? :entity/website)
                                              (? :entity/meta-description)
                                              (? :entity/description)
                                              (? :entity/domain-name)
                                              (? :entity/locale-default)
                                              (? :entity/locale-dicts)
                                              (? :entity/locale-suggestions)
                                              (? :entity/social-feed)
                                              (? :entity/deleted-at)
                                              (? :entity/created-by)

                                              (? :board/custom-css)
                                              (? :board/custom-js)
                                              (? :board/home-page-message)
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
                                              (? :board/invite-email-text)
                                              (? :board/registration-page-message)
                                              (? :board/registration-newsletter-field?)
                                              (? :board/registration-url-override)
                                              (? :board/project-numbers?)
                                              (? :board/slack.team)
                                              (? :board/sticky-color)

                                              (? :member-vote/open?)
                                              (? :webhook/subscriptions)]}})

(q/defx board:register!

  [req {:as params registration-data :body}]
  ;; create membership
  )

#?(:clj
   (defn membership-id [entity-id account-id]
     (dl/entity [:member/entity+account [(dl/resolve-id entity-id)
                                         (dl/resolve-id account-id)]])))

(q/defquery show
  {:prepare [(member.data/member:log-visit! :board-id)
             (az/with-roles :board-id)]}
  [{:keys [board-id member/roles]}]
  (if-let [board (db/pull `[~@entity.data/fields
                            :board/registration-open?
                            {:entity/parent [~@entity.data/fields :org/show-org-tab?]}]
                          board-id)]
    (merge board {:member/roles roles})
    (throw (ex-info "Board not found!" {:status 400}))))

(q/defquery members
  {:prepare [(az/with-roles :board-id)]}
  [{:keys [board-id member/roles]}]
  (->> (db/where [[:member/entity board-id]])
       (remove :entity/archived?)
       (mapv (db/pull `[~@entity.data/fields
                        {:member/account [:entity/id
                                          :entity/kind
                                          {:image/avatar [:entity/id]}
                                          :account/display-name]}]))))

(q/defquery projects
  {:prepare [(az/with-roles :board-id)]}
  [{:keys [board-id member/roles]}]
  (->> (db/where [[:entity/parent board-id]])
       (remove :entity/archived?)
       (mapv (db/pull `[~@entity.data/fields]))))

(defn authorize-edit! [board account-id]
  (when-not (or (validate/can-edit? board account-id)
                (validate/can-edit? (:entity/parent board) account-id))
    (validate/permission-denied!)))

(defn authorize-create! [board account-id]
  (when-not (validate/can-edit? (:entity/parent board) account-id)
    (validate/permission-denied!)))

(q/defx new!
  {:prepare [az/with-account-id!]}
  [{:keys [board account-id]}]
  (let [board  (-> (dl/new-entity board :board :by account-id)
                   (validate/conform :board/as-map))
        _      (authorize-create! board account-id)
        member (-> {:member/entity  board
                    :member/account account-id
                    :member/roles   #{:role/admin}}
                   (dl/new-entity :member))]
    (db/transact! [member])
    board))

(q/defquery settings
  {:prepare [az/with-account-id!
             (az/with-roles :board-id)
             (fn [_ {:as params :keys [member/roles board-id account-id]}]
               (validate/assert-can-edit! board-id account-id)
               params)]}
  [{:keys [board-id member/roles]}]
  (some->
    (q/pull `[~@entity.data/fields
              {:board/member-fields ~field.data/field-keys}
              {:board/project-fields ~field.data/field-keys}] board-id)
    (merge {:member/roles roles})))
(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])

