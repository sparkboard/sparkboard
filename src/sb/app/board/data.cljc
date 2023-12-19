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
   :board/instructions                       {:doc "Secondary instructions for a board, displayed above projects"
                                              s-   :prose/as-map},
   :board/max-projects-per-member            {:doc "Set a maximum number of projects a member may join"
                                              s-   :int}
   :board/sticky-color                       {:doc "Border color for sticky projects"
                                              s-   :html/color}
   :board/member-tags                        (sch/ref :many :tag/as-map)
   :board/project-fields                     (merge (sch/ref :many :field/as-map)
                                                    sch/component)
   :board/member-fields                      (merge (sch/ref :many :field/as-map)
                                                    sch/component)
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

