(ns sparkboard.app.member.ui
  (:require [sparkboard.app.asset.ui :as asset.ui]
            [sparkboard.app.member.data :as data]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.app.views.ui :as ui]))

(ui/defview show
  {:route       "/m/:member-id"
   :view/router :router/modal}
  [params]
  (let [{:as          member
         :member/keys [tags
                       ad-hoc-tags
                       account]} (data/show {:member-id (:member-id params)})
        {:keys [:account/display-name
                :image/avatar]} account]
    [:div
     [:h1 display-name]
     ;; avatar
     ;; fields
     (when-let [tags (seq (concat tags ad-hoc-tags))]
       [:section [:h3 (tr :tr/tags)]
        (into [:ul]
              (map (fn [{:tag/keys [label background-color]}]
                     [:li {:style (when background-color {:background-color background-color})} label]))
              tags)])
     (when avatar [:img {:src (asset.ui/asset-src avatar :card)}])]))