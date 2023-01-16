(ns org.sparkboard.client.views
  (:require
   [clojure.pprint :refer [pprint]]
   [org.sparkboard.routes :refer [path-for]]
   [org.sparkboard.routes :as routes]
   [org.sparkboard.websockets :as ws]
   [yawn.view :as v]))

(v/defview home [] "Nothing to see here, folks.")

(v/defview playground []
  [:div.ma3
   [:a {:href "/skeleton"} "skeleton"]
   [:p (str :list)]
   [:pre.code (with-out-str (pprint (ws/use-route [:list])))]
   [:button.p-2.rounded.bg-blue-100
    {:on-click #(ws/send [:conj!])}
    "List, grow!"]
   [:p (str [:org/index])]
   [:pre.code (with-out-str (pprint (ws/use-route [:org/index])))]])

(v/defview skeleton []
  [:div
   (into [:ul]
         (map (fn [org-obj]
                [:li
                 [:a {:href (routes/path-for :org/view {:org/id (:org/id org-obj)})} (:org/title org-obj)]]))
         (:value (ws/use-route [:org/index])))])

(v/defview org-index []
  (let [orgs (:value (ws/use-query [:org/index]))]
    [:div
     [:h1 "Orgs"]
     [:ul
      (for [{:as o :org/keys [title id]} @orgs]
        [:li [:a
              {:href (path-for :org/view {:org/id id})} title]])]]))

(v/defview org-view [{:as x :org/keys [id]}]
  (let [result (ws/use-route [:org/view {:org/id id}])]
    [:div
     [:h1 (:org/title x)]
     [:pre.code (with-out-str (pprint result))]]))

(v/defview list-view [_]
  "list view...")

(v/defview query-view [{:keys [path]}]
  (let [result (ws/use-route path)]
    [:div.code.pa3
     [:p.f4 [:a {:href "/skeleton"} "up"] (str (:tag (routes/match-route path)))]
     [:pre (with-out-str (pprint result))]]))