(ns sparkboard.views.entity
  (:require [sparkboard.entities.account :as account]
            [sparkboard.entities.entity :as entity]
            [sparkboard.icons :as icons]
            [sparkboard.routes :as routes]
            [sparkboard.views.ui :as ui]
            [yawn.view :as v]
            ))

(defn header [{:as          entity
                      :keys [entity/title
                             image/avatar]} & children]
  (into [:div.entity-header
         (when avatar
           [:a.contents {:href (routes/entity entity :read)}
            [:img.h-10.w-10
             {:src (ui/asset-src avatar :avatar)}]])
         [:a.contents {:href (routes/entity entity :read)} [:h3 title]]
         [:div.flex-grow]]
        (concat children [[account/header:account]])))

(defn edit-btn [{:as entity :keys [entity/kind]}]
  [:a.inline-flex.items-center {:class "hover:text-txt/60"
                                :href  (entity/href entity (keyword (name kind) "edit"))}
   [icons/settings]])