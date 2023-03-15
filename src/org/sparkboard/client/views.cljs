(ns org.sparkboard.client.views
  (:require
   ["react" :as react]
   [applied-science.js-interop :as j]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [inside-out.forms :as forms :refer [with-form]]
   [org.sparkboard.client.common :as common]
   [org.sparkboard.client.sanitize :refer [safe-html]]
   [org.sparkboard.i18n :as i18n :refer [tr use-tr]]
   [org.sparkboard.routes :as routes]
   [org.sparkboard.views.rough :as rough]
   [org.sparkboard.websockets :as ws]
   [org.sparkboard.macros :refer [defview]]
   [yawn.hooks :refer [use-state]]
   [yawn.view :as v]))

(defn http-ok? [rsp]
  (= 200 (.-status rsp)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defview home [] (use-tr [:skeleton/nix]))

(defonce !session
  (common/$local-storage ::session {}))

(defview login [params]
  (with-form [!mbr {:member/name ?mbr-name
                    :member/password ?pwd}]
    [:h2 (use-tr [:tr/login])]
    [:form {:id "login"} ;; shorthand hiccup not available here
     [:label (use-tr [:tr/member-name])]
     [:input {:type "text", :value (or @?mbr-name "")
              :on-change (forms/change-handler ?mbr-name)}]
     [:label (use-tr [:tr/password])]
     [:input {:type "password", :value (or @?pwd "")
              :on-change (forms/change-handler ?pwd)}]
     [rough/button
      {:on-click #(routes/mutate!
                   {:route [:login]
                    :response-fn (fn [^js/Response res _url]
                                   (when (http-ok? res)
                                     ;; TODO set this based on response body
                                     ;; instead (I don't want to wrestle with
                                     ;; readablestream right now --DAL
                                     ;; 2023-03-15)
                                     ;; TODO ...or promise-style with `p/->>`
                                     (reset! !session {:identity @?mbr-name})
                                     (routes/set-location! [:org/index]))
                                   res)}
                   @!mbr)}
      (use-tr [:tr/login])]]))

(defview messaging [{:as msg-thread :message.thread/keys [id]}]
  (let [msg-thread (ws/use-query! [:message.thread/one {:message.thread/id id}])
        msgs       (ws/use-query! [:message/index      {:message.thread/id id}])]
    (when msg-thread
      [:section
       [:h1 (str "messaging: " ;; FIXME i18n
                 (:message.thread/topic msg-thread))]
       (into [:ol]
               (map (comp (partial vector :li)
                          :message/contents))
               msgs)])))

(defview org:index [params]
  [:div.pa3
   [:h2 (use-tr [:tr/orgs])]
   [:section#orgs-grid
    (into [rough/grid {}]
          (map (fn [org]
                 [rough/card {:class "pa3"}
                  [rough/link {:href (routes/path-for [:org/one org])}
                   (:org/title org)]
                  [rough/icon-button
                   {:on-click #(when (js/window.confirm (str "Really delete organization "
                                                             (:org/title org) "?"))
                                 (routes/mutate! {:route [:org/delete]} (:org/id org)))}
                   "X"]]))
          (ws/use-query! [:org/index {}]))]
   [:section#add-org
    [(routes/use-view :org/create)]]
   [messaging {:message.thread/id
               ;; FIXME only for dev:
               "0beff516-ec33-415f-aacd-92f1328e4698"}]])

(defview org:create [params]
  (with-form [!org {:org/title ?title}]
    [:div
     [:h2 (use-tr [:tr/new]) " " (use-tr [:tr/org])]
     [:form
      [:label "Title"]
      [:input {:type "text"
               :value (or @?title "")
               :on-change (forms/change-handler ?title)}]
      [rough/button
       {:on-click #(do (routes/mutate! {:route [:org/create]
                                        :response-fn (fn [^js/Response res _url]
                                                       (case (.-status res)
                                                         200 (js/console.log "200 response")
                                                         400 (js/console.warn "400 response" (.-body res))
                                                         500 (js/console.error "500 response")
                                                         (js/console.error "catch-all case"))
                                                       res)}
                                       @!org)
                       (forms/clear! !org))}
       (str (use-tr [:tr/create]) " " (use-tr [:tr/org]))]]]))

(defview board:create [{:as params :keys [route org/id]}]
  (let [[n n!] (use-state "")]
    [:div
     [:h3 (use-tr [:tr/new]) " " (use-tr [:tr/board])]
     [rough/input {:placeholder "Board title"
                   :value n
                   :on-input #(n! (-> % .-target .-value))}]
     [rough/button {:on-click #(routes/mutate! {:route route
                                                :response-fn (fn [^js/Response res _url]
                                                               (when (http-ok? res)
                                                                 (routes/set-location! [:org/one {:org/id (:org/id params)}])
                                                                 ;; FIXME "Uncaught (in promise) DOMException: The operation was aborted."
                                                                 res))}
                                               {:board/title n})}
      (str (use-tr [:tr/create]) " " (use-tr [:tr/board]))]]))

(defview project:create [{:as params :keys [route board/id]}]
  (let [[n n!] (use-state "")]
    [:div
     [:h3 (str (use-tr [:tr/new]) " " (use-tr [:tr/project]))]
     [rough/input {:placeholder "Project title"
                   :value n
                   :on-input #(n! (-> % .-target .-value))}]
     [rough/button {:on-click #(routes/mutate! {:route route
                                                :response-fn (fn [^js/Response res _url]
                                                               (when (http-ok? res)
                                                                 (routes/set-location! [:board/one {:board/id (:board/id params)}])
                                                                 res))}
                                               {:project/title n})}
      (str (use-tr [:tr/create]) " " (use-tr [:tr/project]))]]))

(defview member:create [{:as params :keys [route board/id]}]
  (let [new-mbr (use-state {:member/name "", :member/password ""})]
    [:div
     [:h3 (str (use-tr [:tr/new]) " " (use-tr [:tr/member]))]
     [rough/input {:placeholder "Member name"
                   :value (:member/name @new-mbr)
                   :on-input #(swap! new-mbr assoc :member/name (-> % .-target .-value))}]
     [rough/input {:placeholder "Member password"
                   :type "password"
                   :value (:member/password @new-mbr)
                   :on-input #(swap! new-mbr assoc :member/password (-> % .-target .-value))}]
     [rough/button {:on-click #(routes/mutate! {:route route
                                                :response-fn (fn [^js/Response res _url]
                                                               (when (http-ok? res)
                                                                 (routes/set-location! [:board/one {:board/id (:board/id params)}])
                                                                 res))}
                                               @new-mbr)}
      (str (use-tr [:tr/create]) " " (use-tr [:tr/member]))]]))

(defview org:one [{:as params :keys [org/id query-params]}]
  (let [value (ws/use-query! [:org/one {:org/id id}])
        [query-params set-query-params!] (use-state query-params)
        search-result (ws/use-query! [:org/search {:org/id id
                                                   :query-params query-params}])
        [pending? start-transition] (react/useTransition)]
    [:div
     [:h1 (use-tr [:tr/org]) " " (:org/title value)]
     [:a {:href (routes/path-for [:board/create params])}
      (use-tr [:tr/new]) " " (use-tr [:tr/board])]
     [:p (-> value :entity/domain :domain/name)]
     (let [[q set-q!] (yawn.hooks/use-state-with-deps (:q query-params) (:q query-params))]
       [:section
        [:h3 (use-tr [:tr/search])]
        [rough/search-input
         {:id "org-search", :placeholder (use-tr [:tr/search-across-org])
          :type "search"
          :on-input (fn [event] (-> event .-target .-value set-q!))
          :on-key-down (j/fn [^js {:keys [key]}]
                         (when (= key "Enter")
                           (start-transition
                            #(-> {:q (when (<= 3 (count q)) q)}
                                 routes/merge-query!
                                 set-query-params!))))
          :value q}]
        (when pending? [:div [rough/spinner {:duration 300}]])
        (into [:ul]
              (map (comp (partial vector :li)
                         str))
              search-result)])
     [:section [:h3 (use-tr [:tr/boards])]
      (into [:ul]
            (map (fn [board]
                   [:li [rough/link {:href (routes/path-for [:board/one board])} ;; path-for knows which key it wants (:board/id)
                         (:board/title board)]]))
            (:board/_org value))]]))

(defview board:one [{:as b :board/keys [id]}]
  (let [value (ws/use-query! [:board/one {:board/id id}])]
    [:<>
     [:h1 (str (use-tr [:tr/board]) (:board/title value))]
     [:p (-> value :entity/domain :domain/name)]
     [:blockquote
      [safe-html (-> value
                     :board.landing-page/description-content
                     :text-content/string)]]
     [rough/tabs {:class "w-100"}
      [rough/tab {:name (use-tr [:tr/projects])
                  :class "db"}
       [:a {:href (routes/path-for [:project/create b])}
        (use-tr [:tr/new]) " " (use-tr [:tr/project])]
       (into [:ul]
             (map (fn [proj]
                    [:li [:a {:href (routes/path-for [:project/one proj])}
                          (:project/title proj)]]))
             (:project/_board value))]
      [rough/tab {:name (use-tr [:tr/members])
                  :class "db"}
       [:a {:href (routes/path-for [:member/create b])}
        (use-tr [:tr/new]) " " (use-tr [:tr/member])]
       (into [:ul]
             (map (fn [member]
                    [:li
                     [:a {:href (routes/path-for [:member/one member])}
                      (:member/name member)]]))
             (:member/_board value))]
      [rough/tab {:name "I18n" ;; FIXME any spaces in the tab name cause content to break; I suspect a bug in `with-props`. DAL 2023-01-25
                  :class "db"}
       [:ul ;; i18n stuff
        [:li "suggested locales:" (str (:i18n/suggested-locales value))]
        [:li "default locale:" (str (:i18n/default-locale value))]
        [:li "extra-translations:" (str (:i18n/extra-translations value))]]]]]))

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

(defview project:one [{:as p :project/keys [id]}]
  (let [value (ws/use-query! [:project/one {:project/id id}])]
    [:div
     [:h1 (str (use-tr [:tr/project]) " " (:project/title value))]
     [:blockquote (:project/summary-text value)]
     (when-let [badges (:project.admin/badges value)]
       [:section [:h3 (tr [:tr/badges])]
        (into [:ul]
              (map (fn [bdg] [:li (:badge/label bdg)]))
              badges)])
     (when-let [vid (:project/video value)]
       [:section [:h3 (tr [:tr/video])]
        [video-field vid]])]))

(defview member:one [{:as mbr :member/keys [id]}]
  (let [value (ws/use-query! [:member/one {:member/id id}])]
    [:div
     [:h1 (str/join " " [(use-tr [:tr/member]) (:member/name value)])]
     (when-let [tags (seq (concat (:member/tags value)
                                  (:member/tags.custom value)))]
       [:section [:h3 (tr [:tr/tags])]
        (into [:ul]
              (map (fn [tag]
                     (if (:tag.ad-hoc/label tag)
                       [:li (:tag.ad-hoc/label tag)]
                       [:li [:span (when-let [bg (:tag/background-color tag)]
                                     {:style {:background-color bg}})
                             (:tag/label tag)]])))
              tags)])
     [:img {:src (:member/image-url value)}]]))

;; for DEBUG only:

(defview show-query [[id :as route]]
  (when (:query (@routes/!routes id))
    (let [value (ws/use-query! route)
          value-str (yawn.hooks/use-memo
                     (fn [] (with-out-str (pprint value)))
                     (yawn.hooks/use-deps value))]
      [:pre value-str])))

(defview drawer [{:keys [initial-height]} child]
  ;; the divider is draggable and sets the height of the drawer
  (let [!height (yawn.hooks/use-state initial-height)]
    [:Suspense {:fallback (v/x [rough/spinner {:duration 1000 :spinning true}])}
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

(defview global-header [_]
  [:<>
   [:section [:span (use-tr [:tr/user]) ":" (:identity @!session "<not logged in>")]]
   [:section
    [:label {:for "language-selector"} (use-tr [:tr/lang])]
    (into [:select {:id "language-selector"
                    :default-value (name @i18n/!preferred-language)
                    :on-change (fn [event]
                                 (reset! i18n/!preferred-language
                                         (-> event .-target .-value keyword)))}]
          (map (fn [lang] [:option {:value (name lang)}
                           (get-in i18n/dict [lang :meta/lect])]))
          (keys i18n/dict))]
   [rough/button
    {:on-click #(do (reset! !session {})
                    (routes/mutate!
                     {:route [:logout]
                      :response-fn (fn [^js/Response res _url]
                                     (when (http-ok? res)
                                       (routes/set-location! [:login]))
                                     res)}))}
    (use-tr [:tr/logout])]
   [rough/divider]])

(defview dev-drawer [{:as match :keys [route]}]
  [drawer {:initial-height 100}
   [:Suspense {:fallback (v/x "Hi")}
    (into [:p.f5]
          (for [[id :as route] (routes/breadcrumb (:path @routes/!current-location))]
            [:a.mr3.rounded.bg-black.white.pa2.no-underline
             {:href (routes/path-for route)} (str id)]))
    (str route)
    [show-query route]]])
