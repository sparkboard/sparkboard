(ns sparkboard.entities.entity 
  (:require [sparkboard.routes :as routes]
            [sparkboard.views.ui :as ui]
            [yawn.view :as v]))

(defn route [{:as e :entity/keys [kind id]} key]
  (when e
    (let [tag (keyword (name kind) (name key))]
      (routes/path-for tag kind id))))

(ui/defview card
  {:key :entity/id} 
  [{:as   entity
    :keys [entity/title image/logo]}]
  [:a.flex.relative
   {:href (routes/entity entity :read)
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
    [:div.line-clamp-2 title]]])



