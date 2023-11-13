(ns sparkboard.entity
  (:require [clojure.set :as set]
            [malli.util :as mu]
            [re-db.api :as db]
            [sparkboard.app.domain :as domains]
            [sparkboard.authorize :as az]
            [sparkboard.routes :as routes]
            [sparkboard.schema :as sch]
            [sparkboard.util :as u]
            [sparkboard.validate :as validate]
            [sparkboard.ui :as ui]
            [yawn.view :as v]
            [sparkboard.query :as q]
            #?(:clj [sparkboard.server.datalevin :as dl])))

;; common entity fields
(def fields [:entity/id
             :entity/kind
             :entity/title
             :entity/description
             :entity/created-at
             :entity/deleted-at
             {:image/avatar [:asset/id]}
             {:image/background [:asset/id]}
             {:entity/domain [:domain/name]}])

(defn account-as-entity [account]
  (u/select-as account {:entity/id            :entity/id
                        :account/display-name :entity/title
                        :image/avatar         :image/avatar}))

#?(:cljs
   (defn href [{:as e :entity/keys [kind id]} key]
     (when e
       (let [tag (keyword (name kind) (name key))]
         (routes/href [tag {(keyword (str (name kind) "-id")) id}])))))


(ui/defview card:compact
  {:key :entity/id}
  [{:as   entity
    :keys [entity/title image/avatar]}]
  [:a.flex.relative
   {:href  (routes/href (routes/entity-route entity :show))
    :class ["sm:divide-x sm:shadow sm:hover:shadow-md "
            "overflow-hidden rounded-lg"
            "h-12 sm:h-16 bg-card text-card-txt border border-white"]}
   (when avatar
     [:div.flex-none
      (v/props
        (merge {:class ["w-12 sm:w-16"
                        "bg-no-repeat sm:bg-secondary bg-center bg-contain"]}
               (when avatar
                 {:style {:background-image (ui/css-url (ui/asset-src avatar :avatar))}})))])
   [:div.flex.items-center.px-3.leading-snug
    [:div.line-clamp-2 title]]])

(ui/defview row
  {:key :entity/id}
  [{:as   entity
    :keys [entity/title image/avatar]}]
  [:a.flex.relative.gap-3.items-center.hover:bg-gray-100.rounded-lg.p-2
   {:href (routes/entity entity :show)}
   [ui/avatar {:size 10} entity]
   [:div.line-clamp-2.leading-snug title]])

#?(:cljs
   (ui/defview card:pprinted [x]
     [:pre.flex.text-sm.overflow-scroll (ui/pprinted x)]))

#?(:clj
   (defn conform [m schema]
     (-> m
         (domains/conform-and-validate)
         (validate/assert (-> (mu/optional-keys schema)
                              (mu/assoc :entity/domain (mu/optional-keys :domain/as-map)))))))

#?(:clj
   (defn can-edit? [entity-id account-id]
     (let [entity-id  (dl/resolve-id entity-id)
           account-id (dl/resolve-id account-id)]
       (or (= entity-id account-id)                         ;; entity _is_ account
           (->> (dl/entity [:member/entity+account [entity-id account-id]])
                :member/roles
                (some #{:role/owner :role/admin :role/collaborate})
                boolean)))))

#?(:clj
   (defn assert-can-edit! [entity-id account-id]
     (when-not (can-edit? entity-id account-id)
       (throw (ex-info "Validation failed"
                       {:response {:status 400}})))))

#?(:cljs
   (v/defview show-filtered-results
     {:key :title}
     [{:keys [q title results]}]
     (when-let [results (seq (sequence (ui/filtered q) results))]
       [:div.mt-6 {:key title}
        (when title [:div.px-body.font-medium title [:hr.mt-2.sm:hidden]])
        (into [:div.card-grid]
              (map card:compact)
              results)])))

(q/defx save-attribute!
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} e a v]
  (let [e (sch/wrap-id e)
        _ (assert-can-edit! e account-id)
        {:as entity :keys [entity/kind entity/id]} (db/entity e)
        pv (get entity a)]
    (validate/assert v a)
    (db/transact! [{:db/id e a v}])
    {:db/id id}))