(ns sparkboard.entities.member
  (:require [sparkboard.i18n :refer [tr]]
            [sparkboard.views.ui :as ui]))

(ui/defview read [{{:member/keys [tags ad-hoc-tags account]} :data}]
  (let [{:keys [:account/display-name :image/avatar]} account]
    [:div
     [:h1 display-name]
     (when-let [tags (seq (concat tags ad-hoc-tags))]
       [:section [:h3 (tr :tr/tags)]
        (into [:ul]
              (map (fn [{:tag/keys [label background-color]}]
                     [:li {:style (when background-color {:background-color background-color})} label]))
              tags)])
     (when avatar [:img {:src (ui/asset-src avatar :card)}])]))