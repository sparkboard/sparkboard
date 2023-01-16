(ns org.sparkboard.client.views
  (:require
   [clojure.pprint :refer [pprint]]
   [org.sparkboard.routes :refer [path-for]]
   [org.sparkboard.routes :as routes]
   [org.sparkboard.websockets :as ws]
   [yawn.view :as v]))

(v/defview home [] "Nothing to see here, folks.")

(v/defview skeleton []
  [:div.pa3
   [:h2 "Organizations"]
   (into [:ul]
         (map (fn [org-obj]
                [:li
                 [:a {:href (routes/path-for :org/view {:org/id (:org/id org-obj)})} (:org/title org-obj)]]))
         (:value (ws/use-query [:org/index])))
   [:h2 "Playground"]
   [:a {:href (routes/path-for :list)} "List"]])

(v/defview org-index []
  (let [orgs (:value (ws/use-query [:org/index]))]
    [:div
     [:h1 "Orgs"]
     [:ul
      (for [{:as o :org/keys [title id]} @orgs]
        [:li [:a
              {:href (path-for :org/view {:org/id id})} title]])]]))

(v/defview org-view [{:as x :org/keys [id]}]
  (let [result (ws/use-query [:org/view {:org/id id}])]
    [:div
     [:h1 "Org: " (-> result :value :org/title)]
     [:pre.code (with-out-str (pprint result))]]))

(v/defview list-view [{:keys [path]}]
  (let [result (ws/use-query path)]
    [:div.code.pa3
     [:p.f4 [:a {:href "/skeleton"} "up"] (str (:tag (routes/match-route path)))]
     [:button.p-2.rounded.bg-blue-100
      {:on-click #(ws/send [:conj!])}
      "List, grow!"]
     [:pre (with-out-str (pprint result))]]))

(v/defview query-view [{:keys [path]}]
  (let [result (ws/use-query path)]
    [:div.code.pa3
     [:h1 "query view"]
     [:p.f4 [:a {:href "/skeleton"} "up"]]
     [:p.f4 (str (:tag (routes/match-route path)))]
     [:pre (with-out-str (pprint result))]]))
