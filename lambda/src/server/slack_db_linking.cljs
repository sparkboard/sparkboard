(ns server.slack-db-linking
  (:require [server.tokens :as tokens]
            [server.firebase :as fire]
            [kitchen-async.promise :as p]
            [server.slack :as slack]
            [cljs.pprint :as pp]
            [applied-science.js-interop :as j]))

;; LINKING
;;
;; Firebase schema -
;; /slack-channel/{id}   => {project-id, workspace-id}
;; /slack-user/{id}      => {account-id, workspace-id}
;; /slack-workspace/{id} => {board-id}

(defn slack-user-by-email
  "Returns slack user id for the given email address, if found"
  [email]
  (p/let [res (slack/get+ "users.lookupByEmail" {:query {:email email}})]
    ;; UNCLEAR: how does Slack know what workspace we are interested in?
    (when (j/get res :ok)
      ;; user contains team_id, id, is_admin
      (j/get res :user))))

;; TEAM ID
;; team - the team ID belonging to a target workspace. Useful when you know which
;; workspace the user should be sent to. When no team is provided or the user is not
;; signed in, users will be sent to their default team or asked to sign in. Use the
;; team.info method to obtain the ID of the workspace your app is currently installed in.

;; Create link entries

(defn link-workspace-to-board!
  "Links slack workspace to sparkboard board, returns board"
  [workspace-id board-id]
  (fire/put+ (str "/slack-workspace/" workspace-id)
             {:body {:board-id board-id}}))

(defn link-channel-to-project!
  [workspace-id channel-id project-id]
  (fire/put+ (str "/slack-channel/" channel-id)
             {:body {:workspace-id workspace-id
                     :project-id project-id}}))

(defn link-user-to-account!
  [workspace-id user-id account-id]
  (fire/put+ (str "/slack-user/" user-id)
             {:body {:workspace-id workspace-id
                     :account-id account-id}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read link entries

;; direct lookups

(defn linked-channel [channel-id]
  (fire/get+ (str "/slack-channel/" channel-id)))

(defn linked-user [user-id]
  (fire/get+ (str "/slack-user/" user-id)))

(defn linked-workspace [workspace-id]
  (fire/get+ (str "/slack-workspace/" workspace-id)))

;; lookups by index

(defn workspace->all-linked-channels [workspace-id]
  (p/->> (fire/get+ (str "/slack-channel")
                    {:query {:orderBy "workspace-id"
                             :equalTo workspace-id}})
         (fire/obj->list :channel-id)))

(defn project->channel [project-id]
  (p/->> (fire/get+ (str "/slack-channel")
                    {:query {:orderBy "project-id"
                             :equalTo project-id
                             :limitToFirst 1}})
         (fire/obj->list :channel-id)))

(defn account->all-slack-users [account-id]
  (p/->> (fire/get+ (str "/slack-user")
                    {:query {:orderBy "account-id"
                             :equalTo account-id}})
         (fire/obj->list :user-id)))

(defn account->workspace-user [workspace-id account-id]
  (p/->> (account->all-slack-users account-id)
         (filter #(= (:workspace-id %) workspace-id))
         first))

(defn board->workspace [board-id]
  (p/->> (fire/get+ "/slack-workspace"
                    {:query
                     {:orderBy "board-id"
                      :startAt board-id
                      :limitToFirst 1}})
         (fire/obj->list :workspace-id)
         first))

(defn user-is-board-admin? [slack-user-id workspace-id]
  (p/let [{:keys [board-id]} (linked-workspace workspace-id)
          {:keys [account-id]} (linked-user slack-user-id)]
    ;; TODO
    ;; ask Sparkboard if account is admin of board,
    ;; OR rely on Slack admin status?
    ))

(defn linking-url-for-slack-id [workspace-id user-id]
  (let [{:keys [board-id]} (linked-workspace workspace-id)
        {:keys [domain]} (fire/get+ (str "/settings/" board-id "/domain"))
        token (tokens/firebase-encode {:user-id user-id
                                       :workspace-id workspace-id})]
    ;; TODO
    ;; create `link-account` endpoint that prompts the user to sign in
    ;; and then shows confirmation screen, before creating the entry
    ;; /slack-user/{user-id} => {:account-id <account-id>
    ;;                           :workspace-id workspace-id}
    (str "https://" domain "/link-account/slack?token=" token)))

(comment

  (defn then-print [& xs]
    (p/then (p/all xs) (comp pp/pprint js->clj)))

  (fire/put+ (str "/slack-workspace/" "WS1" "/parent")
             {:body "demo"})

  ;; create mock linkages
  (then-print
    (link-workspace-to-board! "workspace-1" "board-1")
    (link-channel-to-project! "workspace-1" "channel-1" "project-1")
    (link-user-to-account! "workspace-1" "user-1" "account-1"))

  ;; all direct lookups
  (then-print
    (linked-channel "channel-1")
    (linked-user "user-1")
    (linked-workspace "workspace-1"))

  ;; indexed lookups
  (then-print
    (project->channel "project-1")
    (account->all-slack-users "account-1")
    (account->workspace-user "workspace-1" "account-1")
    (board->workspace "board-1")
    (workspace->all-linked-channels "workspace-1"))

  ;; other lookups
  (then-print
    (slack-user-by-email "mhuebert@gmail.com"))

  )