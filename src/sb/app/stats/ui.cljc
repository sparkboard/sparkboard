(ns sb.app.stats.ui
  (:require [sb.app.stats.data :as data]
            [sb.app.views.ui :as ui]))

(ui/defview show
  {:route "/stats"}
  [params]
  (let [{:keys [orgs boards projects participants
                boards-created-per-year
                projects-created-per-year
                participants-per-year] :as data} (data/show nil)
        years (sort (into #{} (concat (map first boards-created-per-year)
                                      (map first projects-created-per-year)
                                      (map first projects-created-per-year))))
        boards-created-per-year-map (into {} boards-created-per-year)
        projects-created-per-year-map (into {} projects-created-per-year)
        participants-per-year-map (into {} participants-per-year)]


    [:div.p-4
     [:h1.text-xl "Statistics for this instance of Sparkboard"]
     [:table.my-4.border-separate.border-spacing-x-4
      [:tbody
       [:tr
        [:td]
        [:td "total"]
        (for [year years]
          ^{:key year}
          [:td (str year)])]
       [:tr
        [:td "boards"]
        [:td.text-right (str boards)]
        (for [year years]
          ^{:key year}
          [:td.text-right (str (boards-created-per-year-map year "-"))])]
       [:tr
        [:td "projects"]
        [:td.text-right (str projects)]
        (for [year years]
          ^{:key year}
          [:td.text-right (str (projects-created-per-year-map year "-"))])]
       [:tr
        [:td "participants"]
        [:td.text-right (str participants)
         [:span.absolute "*"]]
        (for [year years]
          ^{:key year}
          [:td.text-right (str (participants-per-year-map year "-"))])]]]
     "*the number of total participans is lower than the sum of participants of all years as some people participated in multiple years"

     #_
     [ui/pprinted data]]))
