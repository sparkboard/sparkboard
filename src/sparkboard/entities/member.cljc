(ns sparkboard.entities.member
  (:require [re-db.api :as db]
            [sparkboard.views.ui :as ui]))

(ui/defview read:view [{{:member/keys [tags ad-hoc-tags account]} :data}]
  (let [{:account/keys [display-name photo-url]} account]
    [:div
     [:h1 display-name]
     (when-let [tags (seq (concat tags ad-hoc-tags))]
       [:section [:h3 :tr/tags]
        (into [:ul]
              (map (fn [{:tag/keys [label background-color]}]
                     [:li {:style (when background-color {:background-color background-color})} label]))
              tags)])
     (when photo-url [:img {:src photo-url}])]))

(defn read:query [params]
  (dissoc (db/pull '[*
                     {:member/tags [*]}]
                   [:entity/id (:member params)])
          :member/password))