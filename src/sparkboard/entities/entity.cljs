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
    :keys [entity/title
           image/logo
           image/background]}]
  [:a.shadow.p-3.block.relative.overflow-hidden.rounded.bg-card.pt-24.text-card-txt
   {:href (routes/entity entity :read)}
   [:div.absolute.inset-0.bg-cover.bg-center.h-24.border-b-2
    {:class "bg-card-txt/20 border-card-txt/05"
     :style {:background-image (ui/css-url (ui/asset-src background :card))}}]
   (when logo
     [:div.absolute.inset-0.bg-white.bg-center.bg-contain.rounded.h-10.w-10.mx-3.border.shadow.mt-16
      {:class "border-card-txt/30"
       :style {:background-image (ui/css-url (ui/asset-src logo :logo))}}])
   [:div.font-medium.leading-snug.text-md.mt-5.mb-2 title]])