(ns org.sparkboard.client.views
  (:require
   [org.sparkboard.routes :refer [path-for]]
   [org.sparkboard.websockets :as ws]
   [yawn.view :as v]))

(v/defview org-index []
  (let [orgs (ws/use-query [:org/index])]
    [:div
     [:h1 "Orgs"]
     [:ul
      (for [{:org/keys [title id]} @orgs]
        [:li [:a
              {:href (path-for :org/view :org/id id)} title]])]]))

(v/defview org-view [{:org/keys [title id]}]
  [:div
   [:h1 title]
   [:p (str "id: " id)]])

(v/defview list-view [_]
  "list view...")