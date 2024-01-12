(ns sb.app.account.data
  (:require
    #?(:clj [sb.server.account :as server.account])
    [re-db.api :as db]
    [sb.app.entity.data :as entity.data]
    [sb.authorize :as az]
    [sb.query :as q]
    [sb.schema :as sch :refer [?]]
    [sb.util :as u]))

(sch/register!
  {:account/email               sch/unique-id-str
   :account/email-verified?     {:malli/schema :boolean}
   :account/display-name        {:malli/schema :string
                                 :db/fulltext  true}
   :account.provider.google/sub sch/unique-id-str
   :account/last-sign-in        {:malli/schema 'inst?}
   :account/password-hash       {:malli/schema :string}
   :account/password-salt       {:malli/schema :string}
   :account/locale              {:malli/schema :i18n/locale}
   :account/as-map              {:malli/schema [:map {:closed true}
                                                :entity/id
                                                :account/email
                                                :account/email-verified?
                                                :entity/created-at
                                                (? :account/locale)
                                                (? :account/last-sign-in)
                                                (? :account/display-name)
                                                (? :account/password-hash)
                                                (? :account/password-salt)
                                                (? :image/avatar)
                                                (? :account.provider.google/sub)]}})

(q/defquery account-orgs
  {:endpoint {:query true}
   :prepare  az/with-account-id!}
  [{:keys [account-id]}]
  (into []
        (comp (map :membership/entity)
              (filter (comp #{:org} :entity/kind))
              (map (q/pull entity.data/entity-keys)))
        (db/where [[:membership/account account-id]])))

(q/defquery all
  {:endpoint {:query true}
   :prepare  az/with-account-id!}
  [{:keys [account-id]}]
  (u/timed `all
           (->> (q/pull '[{:membership/_account
                           [:membership/roles
                            {:membership/entity [:entity/id
                                                 :entity/kind
                                                 :entity/title
                                                 :entity/created-at
                                                 {:image/avatar [:entity/id]}
                                                 {:image/background [:entity/id]}]}]}]
                        account-id)
                :membership/_account
                (map #(u/lift-key % :membership/entity)))))

#?(:clj
   (defn login!
     {:endpoint         {:post "/login"}
      :endpoint/public? true}
     [req params]
     (server.account/login! req params)))

#?(:clj
   (defn logout!
     {:endpoint         {:get "/logout"}
      :endpoint/public? true}
     [req params]
     (server.account/logout! req params)))

#?(:clj
   (defn google-landing
     {:endpoint         {:get "/oauth2/google/landing"}
      :endpoint/public? true}
     [req params]
     (server.account/google-landing req params)))


(defn account-as-entity [account]
  (u/select-as account {:entity/id            :entity/id
                        :entity/kind          :entity/kind
                        :account/display-name :entity/title
                        :image/avatar         :image/avatar}))