(ns org.sparkboard.slack.db
  (:require [clojure.pprint :as pp]
            [clojure.set :as set]
            [clojure.string :as str]
            [org.sparkboard.slack.firebase.admin-db-api :as fire]
            [sb.server.env :as env]
            [org.sparkboard.slack.requests :as slack]
            [taoensso.timbre :as log]
            [org.sparkboard.slack.promise :as p]))

(defn join [segments]
  (->> segments (map name) (str/join "/")))
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
  (fire/update-value (str "/slack-team/" team-id)
                     {:body {(str "/app/" app-id) {:bot-token bot-token
                                                   :bot-user-id bot-user-id}
                             :team-name team-name}}))

(defn link-team-to-board! [{:as entry
                            :keys [slack/team-id
                                   sparkboard/board-id]}]
  (p/let [path (str "/slack-team/" team-id "/board-id/")
          existing-board (fire/read path)]
    (cond (nil? existing-board) (fire/set-value path {:body board-id})
          (= existing-board board-id) nil
          :else (throw (ex-info "This Slack team is already linked to a board." {:team-id team-id})))))

(defn link-channel-to-project!
  [{:keys [slack/team-id
           slack/channel-id
           sparkboard/project-id]}]
  (fire/set-value (str "/slack-channel/" channel-id)
                  {:body {:team-id team-id
                          :project-id project-id}}))

(defn link-user-to-account!
  [{:keys [slack/team-id
           slack/user-id
           sparkboard/account-id]}]
  (fire/set-value (str "/slack-user/" user-id)
                  {:body {:team-id team-id
                          :account-id account-id}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Read link entries

;; direct lookups

(defn linked-channel [channel-id]
  (fire/read (str "/slack-channel/" channel-id)))

(defn linked-user [user-id]
  (fire/read (str "/slack-user/" user-id)))

(defn linked-team [team-id]
  (fire/read (str "/slack-team/" team-id)))

(defn bot-token [app-id team-id]
  (fire/read (join [:slack-team team-id :app app-id :bot-token])))

;; lookups by index

(defn team->all-linked-channels [team-id]
  (p/->> (fire/read (str "/slack-channel")
                    {:query [:orderBy "team-id"
                             :equalTo team-id]})
         (fire/map->list :channel-id)))

(defn project->linked-channel [project-id]
  (p/->> (fire/read (str "/slack-channel")
                    {:query [:orderBy "project-id"
                             :equalTo project-id
                             :limitToFirst 1]})
         (fire/map->list :slack/channel-id)
         first
         (#(set/rename-keys % {:team-id :slack/team-id
                               :project-id :sparkboard/project-id}))))

(defn account->all-linked-users [account-id]
  (p/->> (fire/read (str "/slack-user")
                    {:query [:orderBy "account-id"
                             :equalTo account-id]})
         (fire/map->list :slack/user-id)))

(defn account->team-user [{:keys [slack/team-id
                                  sparkboard/account-id]}]
  (some->> (account->all-linked-users account-id)
           (filter #(= (:team-id %) team-id))
           first
           (#(set/rename-keys % {:team-id :slack/team-id
                                 :account-id :sparkboard/account-id}))))

(defn board->team [board-id]
  (some->> (fire/read "/slack-team"
                      {:query [:orderBy "board-id"
                               :equalTo board-id
                               :limitToFirst 1]})
           (fire/map->list :slack/team-id)
           first
           (#(set/rename-keys % {:board-id :sparkboard/board-id
                                 :invite-link :slack/invite-link
                                 :team-name :slack/team-name}))))

(defn board-domain [board-id]
  (fire/read (str "/settings/" board-id "/domain")))

(defn team-context [team-id]
  {:pre [team-id]}
  ;; context we derive from team-id
  (let [app-id (-> env/config :slack :app-id)
        team (linked-team team-id)
        {:keys [bot-token bot-user-id]} (get-in team [:app (keyword app-id)])]
    {:slack/team-id team-id
     :slack/team team
     :slack/team-domain (:domain (slack/team-info bot-token team-id))
     :slack/bot-token bot-token
     :slack/bot-user-id bot-user-id
     :slack/invite-link (:invite-link team)
     :sparkboard/board-id (:board-id team)}))

(defn user-context [user-id]
  ;; context we derive from user-id
  (let [user (linked-user user-id)]
    {:sparkboard/account-id (:account-id user)
     :slack/user-id user-id}))

(defn slack-context
  "Returns context-map expected by functions handling Slack events"
  [team-id user-id]
  {:pre [team-id user-id (string? user-id)]}
  (let [context (merge (team-context team-id)
                       (user-context user-id)
                       {:slack/app-id (-> env/config :slack :app-id)
                        :sparkboard/jvm-root (-> env/config :sparkboard/jvm-root)
                        :env (:env env/config "dev")})]
    (log/debug "[slack-context]:" context)
    context))

(comment

  (defn then-print [& xs]
    (p/-> (p/all xs) ((comp pp/pprint js->clj))))

  (fire/set-value (str "/slack-team/" "WS1" "/parent")
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
