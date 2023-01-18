(ns org.sparkboard.client.views
  (:require
   [clojure.pprint :refer [pprint]]
   [org.sparkboard.routes :as routes]
   [org.sparkboard.websockets :as ws]
   [org.sparkboard.views.rough :as rough]
   [yawn.view :as v]))

(v/defview home [] "Nothing to see here, folks.")

(v/defview org:index []
  [:div.pa3
   [:h2 "Organizations"]
   (into [rough/grid {:style {:gap "1rem"
                              :grid-template-columns "repeat(auto-fit, minmax(200px, 1fr))"}}]
         (map (fn [org-obj]
                [rough/card {:class "pa3"}
                 [rough/link {:href (routes/path-for :org/one {:org/id (:org/id org-obj)})} (:org/title org-obj)]]))
         (:value (ws/use-query [:org/index])))])

(v/defview org:one [{:as o :org/keys [id]}]
  (let [{:keys [value] :as result} (ws/use-query [:org/one {:org/id id}])]
    [:div
     [:h1 "Org: " (:org/title value)]
     [:p (-> value :entity/domain :domain/name)]
     [:section [:h3 "Search"]
      [rough/search-input]]
     [:section [:h3 "Boards"]
      (into [:ul]
            (map (fn [board]
                   [:li [rough/link {:href (routes/path-for :board/one board)} ;; path-for knows which key it wants (:board/id)
                         (:board/title board)]]))
            (:board/_org (:value result)))]]))

(v/defview board:one [{:as b :board/keys [id]}]
  (let [{:keys [value] :as result} (ws/use-query [:board/one {:board/id id}])]
    [:div
     [:h1 (str "Board: " (:board/title value))]
     [:p (-> value :entity/domain :domain/name)]
     [:blockquote (-> value
                      :board.landing-page/description-content
                      :text-content/string)]

     [:section [:h3 "Members"]
      (into [:ul]
            (map (fn [mbr]
                   [:li [rough/link {:href (routes/path-for :member/one mbr)}
                         (:member/name mbr)]]))
            (:member/_board value))]
     [:section [:h3 "Projects"]
      (into [:ul]
            (map (fn [proj] [:li [rough/link {:href (routes/path-for :project/one proj)}
                                  (:project/title proj)]]))
            (-> result :value :project/_board))]]))

(defn youtube-embed [video-id]
  [:iframe#ytplayer {:type "text/html" :width 640 :height 360
                     :frameborder 0
                     :src (str "https://www.youtube.com/embed/" video-id)}])

(defn video-field [[kind v]]
  (case kind
    :field.video/youtube-id (youtube-embed v)
    :field.video/youtube-url [rough/link {:href v} "youtube video"]
    :field.video/vimeo-url [rough/link {:href v} "vimeo video"]
    {kind v}))

(comment
  (video-field [:field.video/youtube-sdgurl "gMpYX2oev0M"])
  )

(v/defview project:one [{:as p :project/keys [id]}]
  (let [{:keys [value] :as result} (ws/use-query [:project/one {:project/id id}])]
    [:div
     [:h1 (str "Project " (:project/title value))]
     [:blockquote (:project/summary-text value)]
     (when-let [badges (:project.admin/badges value)]
       [:section [:h3 "Badges"]
        (into [:ul]
              (map (fn [bdg] [:li (:badge/label bdg)]))
              badges)])
     (when-let [vid (:project/video value)]
       [:section [:h3 "Video"]
        [video-field vid]])]))

(v/defview member:one [{:as mbr :member/keys [id]}]
  (let [{:keys [value] :as result} (ws/use-query [:member/one {:member/id id}])]
    [:div
     [:h1 (str "Member " (:member/name value))]
     (when-let [tags (seq (concat (:member/tags value)
                                  (:member/tags.custom value)))]
       [:section [:h3 "Tags"]
        (into [:ul]
              (map (fn [tag]
                     (if (:tag.ad-hoc/label tag)
                       [:li (:tag.ad-hoc/label tag)]
                       [:li [:span (when-let [bg (:tag/background-color tag)]
                                     {:style {:background-color bg}})
                             (:tag/label tag)]])))
              (concat (:member/tags value)
                      (:member/tags.custom value)))])
     [:img {:src (:member/image-url value)}]]))


;; for DEBUG only:
(v/defview show-query [path]
  (let [{:keys [value error loading?]} (ws/use-query path)]
    (cond loading? [rough/spinner {:spinning true :duration 5000}]
          error [:div "Error: " error]
          value [:pre (with-out-str (pprint value))])))

(v/defview dev-drawer [{:keys [path tag]}]
  [:<>
   [rough/divider]
   [:div.ph3.code
    [:p.f4
     [:a.mr3 {:href (routes/path-for :dev/skeleton)}
      [rough/button "▲"]]
     (str tag)]
    (when (get-in @routes/!routes [tag :query])
      [show-query path])]])

