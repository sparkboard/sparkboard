(ns sb.app.stats.ui
  (:require [sb.app.stats.data :as data]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [yawn.hooks :as h]))

(def participants-note
  "the number of total participants is lower than the sum of participants of all years as some people participated in multiple years")

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
      (when board
        [:tr
         [:td "boards"]
         [:td.text-right (str board)]
         (for [year years]
           ^{:key year}
           [:td.text-right (str (board-year year "-"))])])
      (when project
        [:tr
         [:td "projects"]
         [:td.text-right (str project)]
         (for [year years]
           ^{:key year}
           [:td.text-right (str (project-year year "-"))])])
      (when participant
        (let [discrepancy (not= participant (apply + (vals participant-year)))]
          [:tr
           [:td "participants"]
           [:td.text-right {:title (when discrepancy participants-note)}
            (str participant)
            (when discrepancy
              [:span.absolute "*"])]
           (for [year years]
             ^{:key year}
             [:td.text-right (str (participant-year year "-"))])]))]]))

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

(ui/defview dev
  {:route "/dev-stats"}
  [params]
  (let [stats (sort-by key (update-keys  (data/entity-stats nil) pr-str))
        !current-tab (h/use-state (key (first stats)))]
    (into [radix/tab-root {:class "m-4"
                           :value @!current-tab
                           :on-value-change (partial reset! !current-tab)}
           ;; tabs
           [:div.flex.items-stretch.h-10.gap-3
            [radix/show-tab-list
             (for [x (map key stats)]
               {:title x :value x})]]]

          (for [[kind kind-stats] stats]
            [radix/tab-content {:value kind}
             [:table.border-separate.border-spacing-4
              (into [:tbody]
                    (for [[key key-stats] (sort-by key kind-stats)]
                      [:tr
                       [:td.align-top (str key)]
                       [:td
                        [:table.border-separate.border-spacing-x-1
                         (into [:tbody]
                               (for [[v c] key-stats]
                                 [:tr
                                  [:td.align-top.text-right.font-bold.text-gray-500.font-mono (str c)]
                                  [:td
                                   (if (= ::data/other v)
                                     [:span.text-gray-500 "other"]
                                     [ui/pprinted v])]]))]]]))]]))))
