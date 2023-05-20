(ns sparkboard.entities.member
  (:require [sparkboard.server.query :as query]
            [re-db.api :as db]
            [sparkboard.routes :as routes]
            [sparkboard.views.ui :as ui]))

(ui/defview read:view [{member :data}]
  [:div
   [:h1 (:member/name member)]
   (when-let [tags (seq (concat (:member/tags member)
                                (:member/tags.custom member)))]
     [:section [:h3 :tr/tags]
      (into [:ul]
            (map (fn [tag]
                   (if (:tag.ad-hoc/label tag)
                     [:li (:tag.ad-hoc/label tag)]
                     [:li [:span (when-let [bg (:tag/background-color tag)]
                                   {:style {:background-color bg}})
                           (:tag/label tag)]])))
            tags)])
   [:img {:src (:member/image-url member)}]])

(defn read:query [params]
  (dissoc (db/pull '[*
                     {:member/tags [*]}]
                   [:entity/id (:member params)])
          :member/password))