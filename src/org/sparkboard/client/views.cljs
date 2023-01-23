(ns org.sparkboard.client.views
  (:require
   [applied-science.js-interop :as j]
   [clojure.pprint :refer [pprint]]
   [org.sparkboard.client.sanitize :refer [safe-html]]
   [org.sparkboard.routes :as routes]
   [org.sparkboard.websockets :as ws]
   [org.sparkboard.views.rough :as rough]
   [org.sparkboard.websockets :as ws]
   [yawn.hooks]
   [yawn.view :as v]))

(v/defview home [] "Nothing to see here, folks.")

(v/defview org:index []
  [:div.pa3
   [:h2 "Organizations"]
   (into [rough/grid]
         (map (fn [org-obj]
                [rough/card {:class "pa3"}
                 [rough/link {:href (routes/path-for :org/one {:org/id (:org/id org-obj)})} (:org/title org-obj)]]))
         (:value (ws/use-query [:org/index])))])

(v/defview org:one [{:as o :keys [org/id query-params]}]
  (let [{:keys [value] :as result} (ws/use-query [:org/one {:org/id id}])
        qry-result (ws/use-query [:org/search {:org/id id :query-params query-params}])]
    [:div
     [:h1 "Org: " (:org/title value)]
     [:p (-> value :entity/domain :domain/name)]
     (let [[v set-v!] (yawn.hooks/use-state nil)] ;; TODO maybe switch to inside-out?
       [:section [:h3 "Search"]
        ;; no "rough" here because it doesn't accept props like `id` and `on-change`
        [:input {:id "org-search", :placeholder "org-wide search"
                 :type "search"
                 :on-change (fn [event] (-> event .-target .-value set-v!))
                 ;; TODO search on "enter" keypress
                 :value v}]
        [rough/button {:on-click #(when (<= 3 (count v)) ;; FIXME don't silently do nothing on short entries
                                    (routes/assoc-query! :q v))}
         "search"]
        (into [:ul]
              (map (comp (partial vector :li)
                          str))
              (:value qry-result))])
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
     [:p (-> value :entity/domain :domain/name)]]
     [:blockquote
      [safe-html (-> value
                     :board.landing-page/description-content
                     :text-content/string)]]
     [rough/tabs {:class "w-100"}
      [rough/tab {:name "Projects"
                  :class "db"}
       (into [:ul]
             (map (fn [proj]
                    [:li [:a {:href (routes/path-for :project/one proj)}
                          (:project/title proj)]]))
             (-> result :value :project/_board))]
      [rough/tab {:name "Members"
                  :class "db"}
       (into [:ul]
             (map (fn [mbr]
                    [:li
                     [:a {:href (routes/path-for :member/one mbr)}
                      (:member/name mbr)]]))
             (:member/_board value))]]))

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
  (let [{:keys [value error loading?]} (ws/use-query path)
        value (yawn.hooks/use-memo
               (fn [] (with-out-str (pprint value)))
               (yawn.hooks/use-deps value))]
    (cond loading? [rough/spinner {:duration 1000 :spinning true}]
          error [:div "Error: " error]
          value [:pre value])))

(v/defview drawer [{:keys [initial-height]} child]
  ;; the divider is draggable and sets the height of the drawer
  (let [!height (yawn.hooks/use-state initial-height)]
    [:<>
     [:div {:style {:height @!height}}]
     [:div.ph3.code.fixed.bottom-0.left-0.right-0.bg-white.overflow-y-scroll
      {:style {:height @!height}}
      [:div.bg-black
       {:class "bg-black absolute top-0 left-0 right-0"
        :style {:height 5
                :cursor "ns-resize"}
        :on-mouse-down (j/fn [^js {:as e starting-y :clientY}]
                         (.preventDefault e)
                         (let [on-mousemove (j/fn [^js {new-y :clientY}]
                                              (let [diff (- new-y starting-y)]
                                                (reset! !height (max 5 (- @!height diff)))))
                               on-mouseup (fn []
                                            (.removeEventListener js/window "mousemove" on-mousemove))]
                           (doto js/window
                             (.addEventListener "mouseup" on-mouseup #js{:once true})
                             (.addEventListener "mousemove" on-mousemove))))}]
      child]]))

(v/defview dev-drawer [{:keys [fixed?]} {:keys [path tag]}]
  (cond->> [:<>
            [:p.f5
             [:a.mr3.rounded.bg-black.white.pa2.no-underline
              {:href (routes/path-for :dev/skeleton)} "❮"]
             (str tag)]
            (when (get-in @routes/!routes [tag :query])
              [show-query path])]
           fixed? (drawer {:initial-height 100})))

