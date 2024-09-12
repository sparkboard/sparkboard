(ns sb.app.account.data
  (:require
    #?(:clj [sb.server.account :as server.account])
    [re-db.api :as db]
    [sb.app.entity.data :as entity.data]
    [sb.authorize :as az]
    [sb.query :as q]
    [sb.schema :as sch :refer [?]]
    [sb.server.datalevin :as dl]
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
  {:prepare  az/with-account-id!}
  [{:keys [account-id]}]
  (into []
        (comp (map :membership/entity)
              (filter (comp #{:org} :entity/kind))
              (map (q/pull entity.data/listing-fields)))
        (db/where [[:membership/member account-id]])))

(comment
  (let [account-id [:entity/id #uuid "b03a4669-a7ef-3e8e-bddc-8413e004c338"]]
    (u/timed `all
             (->> (q/pull `[{:membership/_member
                             [:membership/roles
                              :entity/id
                              :entity/kind
                              {:membership/entity [:entity/id
                                                   :entity/kind
                                                   :entity/title
                                                   :entity/created-at
                                                   {:image/avatar [:entity/id]}
                                                   {:image/background [:entity/id]}]}
                              {:membership/_member :...}]}] account-id)
                  :membership/_member
                  (mapcat #(cons % (:membership/_member %)))
                  (map #(assoc (:membership/entity %) :membership/roles (:membership/roles %)))))))

(q/defquery all
  {:prepare  az/with-account-id!}
  [{:keys [account-id]}]
  (u/timed `all
           (->> (q/pull `[{:membership/_member
                           [:membership/roles
                            :entity/id
                            :entity/kind
                            {:membership/member [~@entity.data/id-fields
                                                 :account/display-name
                                                 {:image/avatar [:entity/id]}]}
                            {:membership/entity [~@entity.data/listing-fields
                                                 {:entity/parent [~@entity.data/listing-fields]}
                                                 {:image/background [:entity/id]}]}
                            {:membership/_member :...}]}]
                        account-id)
                :membership/_member
                #_(mapcat #(cons % (:membership/_member %)))
                (map #(assoc-in % [:membership/entity :membership/roles] (:membership/roles %)))
                #_clojure.pprint/pprint)))

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
