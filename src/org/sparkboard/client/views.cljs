(ns org.sparkboard.client.views
  (:require
   [applied-science.js-interop :as j]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [org.sparkboard.client.sanitize :refer [safe-html]]
   [org.sparkboard.routes :as routes]
   [org.sparkboard.websockets :as ws]
   [org.sparkboard.views.rough :as rough]
   [taoensso.tempura :as tempura]
   [yawn.hooks]
   [yawn.view :as v]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Internationalization / i18n

(def i18n-dict
  "Tempura-style dictionary of internationalizations, keyed by ISO-639-3.
  See https://iso639-3.sil.org/code_tables/639/data/all for list of codes"
  {:eng {:missing ":eng missing text"
         ;; A `lect` is what a language or dialect variety is called; see
         ;; https://en.m.wikipedia.org/wiki/Variety_(linguistics)
         :meta/lect "English"
         ;; Translations
         :skeleton/nix "Nothing to see here, folks." ;; keyed separately from `tr` to mark it as dev-only
         :tr {:lang "Language"
              :search "Search"
              :org "Organisation", :orgs "Organisations"
              :boards "Boards"
              :project "Project", :projects "Projects"
              :member "Member", :members "Members"
              :tag "Tag", :tags "Tags"
              :badge "Badge", :badges "Badges"}}

   :fra {:missing ":fra texte manquant"
         :meta/lect "Français"
         :skeleton/nix "Rien à voir ici, les amis."
         :tr {:lang "Langue"
              :search "Rechercher"
              :org "Organisation", :orgs "Organisations"
              :boards "Tableaux"
              :project "Projet", :projects "Projets"
              :member "Membre", :members "Membres"
              :tag "Mot-clé", :tags "Mots clés"
              :badge "Insigne", :badges "Insignes"}}})

(def tr
  ;; TODO user-selected language with `:eng` fallback
  (partial tempura/tr {:dict i18n-dict}))

;; TODO replace all `[:fra]` with user-selected language


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(v/defview home [] (tr [:fra] [:skeleton/nix]))

(v/defview org:index []
  [:div.pa3
   [:h2 (tr [:fra] [:tr/orgs])]
   (into [rough/grid {}]
         (map (fn [org-obj]
                [rough/card {:class "pa3"}
                 [rough/link {:href (routes/path-for :org/one {:org/id (:org/id org-obj)})}
                  (:org/title org-obj)]]))
         (:value (ws/use-query [:org/index])))])

(v/defview org:one [{:as o :keys [org/id query-params]}]
  (let [{:keys [value] :as result} (ws/use-query [:org/one {:org/id id}])
        qry-result (ws/use-query [:org/search {:org/id id :query-params query-params}])]
    [:div
     [:h1 (tr [:fra] [:tr/org]) " " (:org/title value)]
     [:p (-> value :entity/domain :domain/name)]
     (let [[v set-v!] (yawn.hooks/use-state nil)] ;; TODO maybe switch to inside-out?
       [:section [:h3 (tr [:fra] [:tr/search])]
        ;; no "rough" here because it doesn't accept props like `id` and `on-change`
        [:input {:id "org-search", :placeholder "org-wide search"
                 :type "search"
                 :on-change (fn [event] (-> event .-target .-value set-v!))
                 ;; TODO search on "enter" keypress
                 :value v}]
        [rough/button {:on-click #(when (<= 3 (count v)) ;; FIXME don't silently do nothing on short entries
                                    (routes/assoc-query! :q v))}
         (tr [:fra] [:tr/search])]
        (into [:ul]
              (map (comp (partial vector :li)
                          str))
              (:value qry-result))])
     [:section [:h3 (tr [:fra] [:tr/boards])]
      (into [:ul]
            (map (fn [board]
                   [:li [rough/link {:href (routes/path-for :board/one board)} ;; path-for knows which key it wants (:board/id)
                         (:board/title board)]]))
            (:board/_org (:value result)))]]))

(v/defview board:one [{:as b :board/keys [id]}]
  (let [{:keys [value] :as result} (ws/use-query [:board/one {:board/id id}])]
    [:div
     [:h1 (str (tr [:fra] [:tr/board]) (:board/title value))]
     [:p (-> value :entity/domain :domain/name)]]
     [:blockquote
      [safe-html (-> value
                     :board.landing-page/description-content
                     :text-content/string)]]
     [rough/tabs {:class "w-100"}
      [rough/tab {:name (tr [:fra] [:tr/projects])
                  :class "db"}
       (into [:ul]
             (map (fn [proj]
                    [:li [:a {:href (routes/path-for :project/one proj)}
                          (:project/title proj)]]))
             (-> result :value :project/_board))]
      [rough/tab {:name (tr [:fra] [:tr/members])
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
     [:h1 (str (tr [:fra] [:tr/project]) " " (:project/title value))]
     [:blockquote (:project/summary-text value)]
     (when-let [badges (:project.admin/badges value)]
       [:section [:h3 (tr [:fra] [:tr/badges])]
        (into [:ul]
              (map (fn [bdg] [:li (:badge/label bdg)]))
              badges)])
     (when-let [vid (:project/video value)]
       [:section [:h3 (tr [:fra] [:tr/video])]
        [video-field vid]])]))

(v/defview member:one [{:as mbr :member/keys [id]}]
  (let [{:keys [value] :as result} (ws/use-query [:member/one {:member/id id}])]
    [:div
     [:h1 (str/join " " [(tr [:fra] [:tr/member]) (:member/name value)])]
     (when-let [tags (seq (concat (:member/tags value)
                                  (:member/tags.custom value)))]
       [:section [:h3 (tr [:fra] [:tr/tags])]
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

(v/defview global-header [{:keys [path tag]}]
  [:<>
   [:section
    [:label {:for "language-selector"} (tr [:fra] [:tr/lang])]
    (let [preferred-lang (or (.getItem (.-localStorage js/window)
                                       "sb.preferred-language")
                             "eng")]
      ;; TODO draw the rest of the owl (make this change the translations currently in effect)
      (into [:select {:id "language-selector"
                      :default-value preferred-lang
                      :on-change (fn [event] (.setItem (.-localStorage js/window)
                                                       "sb.preferred-language"
                                                       (-> event .-target .-value)))}]
            (map (fn [lang] [:option {:value (name lang)}
                            (get-in i18n-dict [lang :meta/lect])]))
            (keys i18n-dict)))]
   [rough/divider]])

(v/defview dev-drawer [{:keys [fixed?]} {:keys [path tag]}]
  (cond->> [:<>
            [:p.f5
             [:a.mr3.rounded.bg-black.white.pa2.no-underline
              {:href (routes/path-for :dev/skeleton)} "❮"]
             (str tag)]
            (when (get-in @routes/!routes [tag :query])
              [show-query path])]
           fixed? (drawer {:initial-height 100})))

