(ns org.sparkboard.client.views
  (:require
   [clojure.pprint :refer [pprint]]
   [org.sparkboard.routes :refer [path-for]]
   [org.sparkboard.routes :as routes]
   [org.sparkboard.websockets :as ws]
   [yawn.view :as v]))

(v/defview show-query
  "Debug view. Pretty-prints query for path."
  [{:keys [path]}]
  (let [result (ws/use-query path)]
    [:div.pa3
     ;; [:h1 "query view"]
     ;; [:p.f4 [:a {:href "/skeleton"} "up"]]
     [:p.f4  "Route: " [:span.code (str (:tag (routes/match-route path)))]]
     [:pre (with-out-str (pprint result))]]))

(v/defview home [] "Nothing to see here, folks.")

(v/defview skeleton []
  [:div.pa3
   [:h2 "Organizations"]
   (into [:ul]
         (map (fn [org-obj]
                [:li
                 [:a {:href (routes/path-for :org/one {:org/id (:org/id org-obj)})} (:org/title org-obj)]]))
         (:value (ws/use-query [:org/index])))
   [:h2 "Playground"]
   [:a {:href (routes/path-for :list)} "List"]])

(v/defview org:index []
  (let [orgs (:value (ws/use-query [:org/index]))]
    [:div
     [:h1 "Orgs"]
     (into [:ul]
           (fn [{:as o :org/keys [title id]}]
             [:li [:a {:href (path-for :org/one {:org/id id})} title]])
           @orgs)]))

(v/defview org:one [{:as o :org/keys [id]}]
  (let [result (ws/use-query [:org/one {:org/id id}])]
    [:div
     [:h1 "Org: " (-> result :value :org/title)]
     [:section [:h3 "Boards"]
      (into [:ul]
            (map (fn [board]
                   [:li [:a {:href (routes/path-for :board/one board)} ;; path-for knows which key it wants (:board/id)
                         (:board/title board)]]))
            (:board/_org (:value result)))]
     [:hr]
     (show-query o)]))

(v/defview board:one [{:as b :board/keys [id]}]
  (let [{:keys [value] :as result} (ws/use-query [:board/one {:board/id id}])]
    [:div
     [:h1 (str "Board: " (:board/title value))]
     [:blockquote (-> value
                      :board.landing-page/description-content
                      :text-content/string)]

     [:section [:h3 "Members"]
      (into [:ul]
            (map (fn [mbr]
                   [:li (:member/name mbr)]))
            (:member/_board value))]
     [:section [:h3 "Projects"]
      (into [:ul]
            (map (fn [proj] [:li [:a {:href (routes/path-for :project/one proj)}
                                  (:project/title proj)]]))
            (-> result :value :project/_board))]
     [:hr]
     (show-query b)]))

(v/defview project:one [{:as p :project/keys [id]}]
  (let [{:keys [value] :as result} (ws/use-query [:project/one {:project/id id}])]
    [:div
     [:h1 (str "Project " (:project/title value))]
     [:blockquote (:project/summary-text value)]
     (show-query p)]))

(v/defview list-view [{:keys [path]}]
  (let [result (ws/use-query path)]
    [:div.code.pa3
     [:p.f4 [:a {:href "/skeleton"} "up"] (str (:tag (routes/match-route path)))]
     [:button.p-2.rounded.bg-blue-100
      {:on-click #(ws/send [:conj!])}
      "List, grow!"]
     [:pre (with-out-str (pprint result))]]))

