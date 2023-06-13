(ns sparkboard.entities.entity
  (:require [clojure.set :as set]
            [malli.util :as mu]
            [sparkboard.entities.domain :as domain]
            [sparkboard.routes :as routes]
            [sparkboard.util :as u]
            [sparkboard.validate :as validate]
            [sparkboard.views.ui :as ui]
            [yawn.view :as v]
            #?(:clj [sparkboard.datalevin :as dl])))

;; common entity fields
(def fields [:entity/id 
             :entity/kind 
             :entity/title 
             :entity/description 
             :entity/created-at
             {:image/logo [:asset/id]}
             {:image/background [:asset/id]}
             {:entity/domain [:domain/name]}])

(defn account-as-entity [account]
  (u/select-as account {:entity/id :entity/id
                        :account/display-name :entity/title
                        :account/photo :image/logo}))

(defn href [{:as e :entity/keys [kind id]} key]
  (when e
    (let [tag (keyword (name kind) (name key))]
      (routes/href tag kind id))))

#?(:cljs
   (ui/defview card
     {:key :entity/id}
     [{:as   entity
       :keys [entity/title image/logo]}]
     [:a.flex.relative
      {:href  (routes/entity entity :read)
       :class ["sm:divide-x sm:shadow sm:hover:shadow-md "
               "overflow-hidden rounded-lg"
               "h-12 sm:h-16 bg-card text-card-txt border border-white"]}
      [:div.flex-none
       (v/props
         (merge {:class ["w-12 sm:w-16"
                         "bg-no-repeat sm:bg-secondary bg-center bg-contain"]}
                (when logo
                  {:style {:background-image (ui/css-url (ui/asset-src logo :logo))}})))]
      [:div.flex.items-center.px-3.leading-snug
       [:div.line-clamp-2 title]]]))

#?(:clj
   (defn conform [m schema]
     (-> m
         (domain/conform-and-validate)
         (validate/assert  (-> (mu/optional-keys schema)
                               (mu/assoc :entity/domain (mu/optional-keys :domain/as-map)))))))

#?(:clj
   (defn can-edit? [entity-id account-id]
     (-> (dl/entity [:member/entity+account [entity-id account-id]])
         :member/roles
         (set/intersection #{:role/admin :role/collaborator})
         seq
         boolean)))