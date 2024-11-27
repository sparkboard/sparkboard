(ns sb.app.board.data
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [sb.app.entity.data :as entity.data]
            [sb.app.field.data :as field.data]
            [sb.app.field.data :as field.data]
            [sb.app.membership.data :as member.data]
            [sb.authorize :as az]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.util :as u]
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
   :board/invite-email-text              {:hint "Text of email sent when inviting a user to a board."
                                          s-    :string},
   :board/registration-newsletter-field? {:hint "During registration, request permission to send the user an email newsletter"
                                          s-    :boolean},,
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

                                              :entity/admission-policy

                                              (? :image/avatar)
                                              (? :image/logo-large)
                                              (? :image/footer)
                                              (? :image/background)
                                              (? :image/sub-header)

                                              (? :entity/meta-description)
                                              (? :entity/description)
                                              (? :entity/domain-name)
                                              (? :entity/locale-default)
                                              (? :entity/locale-dicts)
                                              (? :entity/locale-suggestions)
                                              (? :entity/social-feed)
                                              (? :entity/deleted-at)
                                              (? :entity/created-by)
                                              (? :entity/uploads)
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

                                              (? :member-vote/open?)
                                              (? :webhook/subscriptions)]}})

(q/defx board:register!

  [req {:as params registration-data :body}]
  ;; create membership
  )

(q/defquery show
  {:endpoint/public? true
   :prepare [az/with-account-id
             (az/with-roles :board-id)
             (member.data/assert-can-view :board-id)]}
  [{:as params :keys [account-id board-id membership/roles]}]
  (u/timed `show
    (if-let [board (db/pull `[~@entity.data/listing-fields
                              ~@entity.data/site-fields
                              {:image/logo-large [:entity/id]}
                              {:image/footer [:entity/id]}
                              {:image/sub-header [:entity/id]}
                              :entity/member-tags
                              :entity/member-fields
                              :entity/project-fields
                              :entity/admission-policy
                              :member-vote/open?
                              :board/home-page-message
                              {:entity/parent [~@entity.data/listing-fields :org/show-org-tab?]}]
                            board-id)]
      (merge board {:membership/roles roles
                    :membership (when account-id
                                  (->> (:db/id (not-empty (az/raw-membership account-id board-id)))
                                       (db/pull `[~@entity.data/id-fields
                                                  {:membership/entity [:entity/id]}
                                                  {:membership/member [:entity/id]}
                                                  :entity/deleted-at])))})
      (throw (ex-info "Board not found!" {:status 400})))))

(def board-membership-fields `[{:membership/member ~entity.data/listing-fields}
                              {:entity/tags [:entity/id
                                             :tag/label
                                             :tag/color]}
                              :entity/field-entries
                               :membership/member-approval-pending?
                              {:membership/entity [:entity/id]}
                              {:entity/custom-tags [:tag/label]}
                              :membership/roles])

(def member-fields `[:entity/deleted-at
                     ~@entity.data/id-fields
                     :membership/member-approval-pending?
                     :membership/roles
                     {:membership/entity [:entity/id]}
                     {:membership/member [:entity/id]}])

(def note-fields `[~@entity.data/listing-fields
                   :note/outline-color
                   :entity/fields
                   :entity/field-entries
                   {:entity/video [:video/url]}
                   {:entity/parent [:entity/id]}
                   {:membership/_entity ~member-fields}])

(def project-fields `[~@entity.data/listing-fields
                      :entity/field-entries
                      :project/open-requests
                      :project/number
                      {:entity/video [:video/url]}
                      {:entity/parent [:entity/id]}
                      {:membership/_entity ~member-fields}])

(q/defquery members
  {:prepare [(az/with-roles :board-id)
             (member.data/assert-can-view :board-id)]}
  [{:keys [board-id]}]
  (u/timed `members (->> (db/entity board-id)
                         :membership/_entity
                         (remove (some-fn sch/deleted? :entity/archived? :membership/member-approval-pending?))
                         (mapv (db/pull `[~@entity.data/id-fields
                                          ~@board-membership-fields])))))

(q/defquery pending-members
  {:prepare [(az/with-roles :board-id)
             (member.data/assert-can-view :board-id)]}
  [{:keys [board-id]}]
  (u/timed `pending-members
    (->> (db/entity board-id)
         :membership/_entity
         (remove (some-fn sch/deleted? :entity/archived? (complement :membership/member-approval-pending?)))
         (mapv (db/pull `[~@entity.data/id-fields
                          ~@board-membership-fields])))))

(q/defquery notes
  {:endpoint/public? true
   :prepare [(az/with-roles :board-id)
             (member.data/assert-can-view :board-id)]}
  [{:keys [board-id membership/roles]}]
  (u/timed `notes
           (->> (db/where [[:entity/parent board-id]
                           [:entity/kind :note]])
                (remove (some-fn :entity/draft? sch/deleted? :entity/archived?))
                (mapv (db/pull note-fields)))))

(q/defquery note-drafts
  {:prepare [(az/with-roles :board-id)
             (member.data/assert-can-edit :board-id)]}
  [{:keys [board-id membership/roles]}]
  (u/timed `note-drafts
           (->> (db/where [[:entity/parent board-id]
                           [:entity/kind :note]
                           [:entity/draft? true]])
                (remove sch/deleted?)
                (mapv (db/pull note-fields)))))

(q/defquery projects
  {:endpoint/public? true
   :prepare [(az/with-roles :board-id)
             (member.data/assert-can-view :board-id)]}
  [{:keys [board-id membership/roles]}]
  (u/timed `projects
           (->> (db/where [[:entity/parent board-id]
                           [:entity/kind :project]])
                (remove (some-fn :entity/draft? sch/deleted? :entity/archived?))
                (mapv (db/pull project-fields)))))

(q/defquery project-drafts
  {:prepare az/with-account-id!}
  [{:keys [account-id board-id]}]
  (->> (az/membership account-id board-id)
       not-empty
       member.data/member-of
       (filter (every-pred :entity/draft? (complement sch/deleted?)))
       (mapv (db/pull project-fields))))

(q/defquery ballots
  {:prepare [(az/with-roles :board-id)
             (member.data/assert-can-view :board-id)]}
  [{:as params :keys [board-id membership/roles]}]
  (u/timed `ballots
           (->> (db/where [[:ballot/board board-id]])
                (mapv (db/pull [:entity/id
                                {:ballot/board [:entity/id]}
                                {:ballot/project [:entity/id :entity/kind :entity/title]}
                                {:entity/created-by [:entity/id]}])))))

(q/defquery user-ballot
  {:prepare [az/with-account-id
             (az/with-roles :board-id)
             (member.data/assert-can-view :board-id)]}
  [{:as params :keys [board-id account-id membership/roles]}]
  (u/timed `user-ballot
    (db/pull [:entity/id
              {:ballot/board [:entity/id]}
              {:ballot/project [:entity/id]}
              {:entity/created-by [:entity/id]}]
             [:ballot/board+account (str/join "+" (map sch/unwrap-id [board-id account-id]))])))

(q/defx new!
  {:prepare [az/with-account-id!
             (member.data/assert-can-edit (comp :entity/parent :board))]}
  [{:keys [board account-id]}]
  (let [board  (-> (dl/new-entity board :board :by account-id)
                   (validate/conform :board/as-map))
        member (-> {:membership/entity board
                    :membership/member account-id
                    :membership/roles  #{:role/board-admin}}
                   (dl/new-entity :membership))]
    (db/transact! [member])
    board))

(q/defquery settings
  {:prepare [az/with-account-id!
             (az/with-roles :board-id)
             (member.data/assert-can-edit :board-id)]}
  [{:keys [board-id membership/roles]}]
  (u/timed `settings
           (some->
             (q/pull `[~@entity.data/listing-fields
                       :entity/admission-policy
                       :board/registration-url-override
                       :board/registration-page-message
                       :board/invite-email-text
                       {:image/logo-large [:entity/id]}
                       {:image/sub-header [:entity/id]}
                       {:image/footer [:entity/id]}
                       {:image/background [:entity/id]}
                       {:entity/domain-name [:domain-name/name]}
                       :entity/member-tags
                       {:entity/member-fields ~field.data/field-keys}
                       {:entity/project-fields ~field.data/field-keys}
                       :member-vote/open?]
                     board-id)
             (merge {:membership/roles roles}))))

(comment
  [:ul                                                      ;; i18n stuff
   [:li "suggested locales:" (str (:entity/locale-suggestions board))]
   [:li "default locale:" (str (:i18n/default-locale board))]
   [:li "extra-translations:" (str (:i18n/locale-dicts board))]])

