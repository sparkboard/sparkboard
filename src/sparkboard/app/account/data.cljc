(ns sparkboard.app.account.data
  (:require
    #?(:clj [sparkboard.server.account :as server.account])
    [re-db.api :as db]
    [sparkboard.app.entity.data :as entity.data]
    [sparkboard.authorize :as az]
    [sparkboard.query :as q]
    [sparkboard.schema :as sch :refer [?]]
    [sparkboard.util :as u]))

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
        (comp (map :member/entity)
              (filter (comp #{:org} :entity/kind))
              (map (q/pull entity.data/fields)))
        (db/where [[:member/account account-id]])))

(q/defquery all
  {:endpoint {:query true}
   :prepare  az/with-account-id!}
  [{:keys [account-id]}]
  (->> (q/pull '[{:member/_account [:member/roles
                                    :member/last-visited
                                    {:member/entity [:entity/id
                                                     :entity/kind
                                                     :entity/title
                                                     {:image/avatar [:asset/link
                                                                     :entity/id
                                                                     {:asset/provider [:s3/bucket-host]}]}
                                                     {:image/background [:asset/link
                                                                         :entity/id
                                                                         {:asset/provider [:s3/bucket-host]}]}]}]}]
               account-id)
       :member/_account
       (map #(u/lift-key % :member/entity))))

(q/defquery recent-ids
  {:endpoint {:query true}
   :prepare  az/with-account-id!}
  [params]
  (->> (all params)
       (filter :member/last-visited)
       (sort-by :member/last-visited #(compare %2 %1))
       (into #{} (comp (take 8)
                       (map :entity/id)))))

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
                        :account/display-name :entity/title
                        :image/avatar         :image/avatar}))