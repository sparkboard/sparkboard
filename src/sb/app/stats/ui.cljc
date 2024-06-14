(ns sb.app.stats.ui
  (:require [sb.app.stats.data :as data]
            [sb.app.views.ui :as ui]))

(def participants-note
  "the number of total participans is lower than the sum of participants of all years as some people participated in multiple years")

(ui/defview table [{:keys [board board-year project project-year participant participant-year]}]
  (let [years (->> (concat (keys board-year)
                           (keys project-year)
                           (keys project-year))
                   (into #{})
                   sort)]
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
       [:td.text-right (str board)]
       (for [year years]
         ^{:key year}
         [:td.text-right (str (board-year year "-"))])]
      [:tr
       [:td "projects"]
       [:td.text-right (str project)]
       (for [year years]
         ^{:key year}
         [:td.text-right (str (project-year year "-"))])]
      [:tr
       [:td "participants"]
       [:td.text-right {:title participants-note}
        (str participant)
        [:span.absolute "*"]]
       (for [year years]
         ^{:key year}
         [:td.text-right (str (participant-year year "-"))])]]]))

(ui/defview show
  {:route "/stats"}
  [params]
  (let [{:keys [all by-org] :as data} (data/show nil)]
    [:div.p-4
     [:h1.text-2xl "Statistics for this instance of Sparkboard"]
     [:div.p-4
      [table all]]

     [:h1.text-2xl "By Organization"]
     (for [[org-name data] (sort-by (comp :participant second) > by-org)]
       ^{:key org-name}
       [:div.p-4
        [:h2.text-xl org-name]
        [table data]
        ])

     (str "*" participants-note)
     #_
     [ui/pprinted data]]))
