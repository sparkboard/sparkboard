(ns sb.app.board.data
  (:require [re-db.api :as db]
            [sb.app.field.data :as field.data]
            [sb.app.entity.data :as entity.data]
            [sb.app.member.data :as member.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.validate :as validate]
            [sb.util :as u]
            [sb.app.field.data :as field.data]))

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
                                              (? :entity/member-fields)
                                              (? :entity/member-tags)
                                              (? :board/new-projects-require-approval?)
                                              (? :entity/project-fields)
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

(q/defquery show
  {:prepare [(member.data/member:log-visit! :board-id)
             (az/with-roles :board-id)]}
  [{:keys [board-id member/roles]}]
  (if-let [board (db/pull `[~@entity.data/entity-keys
                            :entity/member-tags
                            :entity/member-fields
                            :entity/project-fields
                            :board/registration-open?
                            {:entity/parent [~@entity.data/entity-keys :org/show-org-tab?]}]
                          board-id)]
    (merge board {:member/roles roles})
    (throw (ex-info "Board not found!" {:status 400}))))

(def project-fields `[~@entity.data/entity-keys])
(def member-fields [{:member/account [:entity/id
                                      :entity/kind
                                      {:image/avatar [:entity/id]}
                                      :account/display-name]}
                    {:entity/tags [:entity/id
                                   :tag/label
                                   :tag/color]}
                    :entity/field-entries
                    {:member/entity [:entity/id]}
                    {:entity/custom-tags [:tag/label]}
                    :member/roles])

(q/defquery members
  {:prepare [(az/with-roles :board-id)]}
  [{:keys [board-id member/roles]}]
  (->> (db/where [[:member/entity board-id]])
       (remove :entity/archived?)
       (mapv (db/pull `[~@entity.data/entity-keys
                        ~@member-fields]))))

(q/defquery projects
  {:prepare [(az/with-roles :board-id)]}
  [{:keys [board-id member/roles]}]
  (->> (db/where [[:entity/parent board-id]])
       (remove (some-fn :entity/draft? :entity/archived?))
       (mapv (db/pull project-fields))))

(q/defquery drafts
  {:prepare az/with-account-id}
  [{:keys [account-id]}]
  (into []
        (comp (filter (comp (every-pred :entity/draft? #(= :project (:entity/kind %))) :member/entity))
              (map #(db/pull project-fields (:member/entity %))))
        (db/where [[:member/account (dl/resolve-id account-id)]])))

(defn authorize-edit! [board account-id]
  (when-not (or (validate/can-edit? account-id board)
                (validate/can-edit? account-id (:entity/parent board)))
    (validate/permission-denied!)))

(defn authorize-create! [board account-id]
  (when-not (validate/can-edit? account-id (:entity/parent board))
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
             (fn [_ {:as params :keys [board-id account-id]}]
               (validate/assert-can-edit! account-id (dl/entity board-id))
               params)]}
  [{:keys [board-id member/roles]}]
  (some->
    (q/pull `[~@entity.data/entity-keys
              :entity/member-tags
              {:entity/member-fields ~field.data/field-keys}
              {:entity/project-fields ~field.data/field-keys}] board-id)
    (merge {:member/roles roles})))
(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])

