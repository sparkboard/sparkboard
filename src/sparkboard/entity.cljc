(ns sparkboard.entity
  (:require [clojure.set :as set]
            [malli.util :as mu]
            [re-db.api :as db]
            [sparkboard.app.domain :as domains]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.validate :as validate]
            [sparkboard.ui :as ui]
            [yawn.view :as v]
            #?(:clj [sparkboard.server.datalevin :as dl])))

;; common entity fields
(def fields `[:entity/id
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


(def account-as-entity-fields
  '[:entity/id
    {:image/avatar [:asset/id]}
    (:account/display-name :as :entity/title)])

#?(:cljs
   (defn href [{:as e :entity/keys [kind id]} key]
     (when e
       (let [tag (keyword (name kind) (name key))]
         (routes/href tag (keyword (str (name kind) "-id")) id)))))

#?(:cljs
   (ui/defview card:compact
     {:key :entity/id}
     [{:as   entity
       :keys [entity/title image/avatar]}]
     [:a.flex.relative
      {:href  (try (routes/href (routes/entity entity :show))
                   (catch js/Error e
                     (js/console.error e)
                     (prn :ERROR entity :routes.entity.show)))
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
       [:div.line-clamp-2 title]]]))

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
     (-> (dl/entity [:member/entity+account [entity-id account-id]])
         :member/roles
         (set/intersection #{:role/admin :role/collaborator})
         seq
         boolean)))

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