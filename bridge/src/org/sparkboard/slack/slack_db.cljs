(ns org.sparkboard.slack.slack-db
  (:require [applied-science.js-interop :as j]
            [cljs.pprint :as pp]
            [kitchen-async.promise :as p]
            [org.sparkboard.firebase-rest :as fire]
            [org.sparkboard.firebase-tokens :as tokens]))

;; Read & write links between Slack and Sparkboard
;;
;; Firebase schema -
;; /slack-channel/{id}   => {project-id, team-id}
;; /slack-user/{id}      => {account-id, team-id}
;; /slack-team/{id} => {board-id}


;; TEAM ID
;; team - the team ID belonging to a target workspace. Useful when you know which
;; workspace the user should be sent to. When no team is provided or the user is not
;; signed in, users will be sent to their default team or asked to sign in. Use the
;; team.info method to obtain the ID of the workspace your app is currently installed in.

;; Create link entries

(defn install-app! [{:as entry
                     :keys [slack/team-id
                            slack/team-name
                            slack/bot-token
                            slack/bot-user-id
                            slack/app-id]}]
  (fire/patch+ (str "/slack-team/" team-id)
               {:body {(str "/app/" app-id) {:bot-token bot-token
                                             :bot-user-id bot-user-id}
                       :team-name team-name}}))

(defn link-team-to-board! [{:as entry
                            :keys [slack/team-id
                                   sparkboard/board-id]}]
  (p/let [path (str "/slack-team/" team-id "/board-id/")
          existing-board (fire/get+ path)]
    (cond (nil? existing-board) (fire/put+ path {:body board-id})
          (= existing-board board-id) nil
          :else (throw (js/Error. "This Slack team is already linked to a board.")))))

(defn link-channel-to-project!
  [{:keys [slack/team-id
           slack/channel-id
           sparkboard/project-id]}]
  (fire/put+ (str "/slack-channel/" channel-id)
             {:body {:team-id team-id
                     :project-id project-id}}))

(defn link-user-to-account!
  [{:keys [slack/team-id
           slack/user-id
           sparkboard/account-id]}]
  (fire/put+ (str "/slack-user/" user-id)
             {:body {:team-id team-id
                     :account-id account-id}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read link entries

;; direct lookups

(defn linked-channel [channel-id]
  (fire/get+ (str "/slack-channel/" channel-id)))

(defn linked-user [user-id]
  (fire/get+ (str "/slack-user/" user-id)))

(defn linked-team [team-id]
  (fire/get+ (str "/slack-team/" team-id)))

(defn team->token [app-id team-id]
  {:pre [(and app-id team-id)]}
  (fire/get+ (str "/slack-team/" team-id "/app/" app-id "/bot-token")))

;; lookups by index

(defn team->all-linked-channels [team-id]
  (p/->> (fire/get+ (str "/slack-channel")
                    {:query {:orderBy "team-id"
                             :equalTo team-id}})
         (fire/obj->list :channel-id)))

(defn project->linked-channel [project-id]
  (p/->> (fire/get+ (str "/slack-channel")
                    {:query {:orderBy "project-id"
                             :equalTo project-id
                             :limitToFirst 1}})
         (fire/obj->list :channel-id)))

(defn account->all-linked-users [account-id]
  (p/->> (fire/get+ (str "/slack-user")
                    {:query {:orderBy "account-id"
                             :equalTo account-id}})
         (fire/obj->list :user-id)))

(defn account->team-user [{:keys [slack/team-id
                                  sparkboard/account-id]}]
  (p/->> (account->all-linked-users account-id)
         (filter #(= (:team-id %) team-id))
         first))

(defn board->team [board-id]
  (p/->> (fire/get+ "/slack-team"
                    {:query
                     {:orderBy "board-id"
                      :equalTo board-id
                      :limitToFirst 1}})
         (fire/obj->list :team-id)
         first))

(defn user-is-board-admin? [slack-user-id team-id]
  (p/let [{:keys [board-id]} (linked-team team-id)
          {:keys [account-id]} (linked-user slack-user-id)]
    ;; TODO
    ;; ask Sparkboard if account is admin of board,
    ;; OR rely on Slack admin status?
    ))

(defn linking-url-for-slack-id [team-id user-id]
  (let [{:keys [board-id]} (linked-team team-id)
        {:keys [domain]} (fire/get+ (str "/settings/" board-id "/domain"))
        token (tokens/encode
                {:user-id user-id
                 :team-id team-id})]
    ;; TODO
    ;; create `link-account` endpoint that prompts the user to sign in
    ;; and then shows confirmation screen, before creating the entry
    ;; /slack-user/{user-id} => {:account-id <account-id>
    ;;                           :team-id team-id}
    (str "https://" domain "/link-account/slack?token=" token)))

(defn get-install-link
  "Returns a link that will lead user to install/reinstall app to a workspace"
  [& [{:as params
       :keys [lambda/root
              lambda/local?
              sparkboard/board-id
              sparkboard/account-id
              slack/team-id]}]]
  {:pre [(or local?                                         ;; dev
             team-id                                        ;; reinstall
             (and board-id account-id)                      ;; new install + link board
             )]}
  (str root "/slack/install?state=" (tokens/encode (dissoc params :lambda/root))))

(comment

  (get-install-link {:lambda/root "https://slack-matt.ngrok.io"
                     :lambda/local? true})

  (defn then-print [& xs]
    (p/then (p/all xs) (comp pp/pprint js->clj)))

  (fire/put+ (str "/slack-team/" "WS1" "/parent")
             {:body "demo"})

  ;; create mock linkages
  (then-print
    (link-team-to-board! {:slack/team-id "team-1"
                          :sparkboard/board-id "board-1"})
    (link-channel-to-project!
      {:slack/team-id "team-1"
       :slack/channel-id "channel-1"
       :sparkboard/project-id "project-1"})
    (link-user-to-account! {:slack/team-id "team-1"
                            :slack/user-id "user-1"
                            :sparkboard/account-id "account-1"}))

  ;; all direct lookups
  (then-print
    (linked-channel "channel-1")
    (linked-user "user-1")
    (linked-team "team-1"))

  ;; indexed lookups
  (then-print
    (project->linked-channel "project-1")
    (account->team-user "team-1" "account-1")
    (board->team "board-1")

    (account->all-linked-users "account-1")
    (team->all-linked-channels "team-1"))

  (then-print
    (link-team-to-board!
      {:slack/team-id "T014098L9FD"
       :sparkboard/board-id "demo"}))
  )
