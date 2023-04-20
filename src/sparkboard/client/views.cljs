(ns sparkboard.client.views
  (:require
   ["react" :as react]
   ["@radix-ui/react-dropdown-menu" :as dm]
   [applied-science.js-interop :as j]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [inside-out.forms :as forms :refer [with-form]]
   [re-db.api :as db]
   [sparkboard.client.sanitize :refer [safe-html]]
   [sparkboard.i18n :as i18n :refer [tr tr]]
   [sparkboard.routes :as routes]
   [sparkboard.views.ui :as ui]
   [sparkboard.websockets :as ws]
   [promesa.core :as p]
   [yawn.hooks :as hooks :refer [use-state]]
   [yawn.view :as v]
   [inside-out.forms :as forms]))

;; TODO
;; - make a yawn.view/classes utility to precompile class strings
;; - /terms, /privacy
;; - clarify Login/Register paths
;; - radix-ui for language selector

(ui/defview menubar:lang []
  [:div.flex.flex-row
   [:el dm/Root
    [:el dm/Trigger
     {:class ["flex justify-center items-center"
              "w-10 h-10 p-2 rounded"
              "bg-zinc-100 hover:bg-zinc-200"
              "cursor-pointer"]}
     [:svg {:class "h-5 w-5" :xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :strokeWidth "{1.5}" :stroke "currentColor"}
      [:path {:strokeLinecap "round" :strokeLinejoin "round" :d "M10.5 21l5.25-11.25L21 21m-9-3h7.5M3 5.621a48.474 48.474 0 016-.371m0 0c1.12 0 2.233.038 3.334.114M9 5.25V3m3.334 2.364C11.176 10.658 7.69 15.08 3 17.502m9.334-12.138c.896.061 1.785.147 2.666.257m-4.589 8.495a18.023 18.023 0 01-3.827-5.802"}]]]
    [:el dm/Portal
     [:el dm/Content {:sideOffset 5 :collision-padding 16}
      (into [:el.shadow.rounded.text-sm dm/RadioGroup
             {:value (name @i18n/!preferred-language)
              :on-value-change (fn [v] (reset! i18n/!preferred-language (keyword v)))}]
            (map (fn [lang] [:el.text-left.flex.pr-4.py-2.cursor-pointer.hover:outline-none.hover:bg-zinc-50
                             dm/RadioItem {:class "data-[disabled]:text-gray-500"
                                           :value (name lang)
                                           :disabled (= lang @i18n/!preferred-language)}
                             [:div.w-6.text-center
                              [:el dm/ItemIndicator "•"]]
                             (get-in i18n/dict [lang :meta/lect])]))
            (keys i18n/dict))]]]
   #_(into [:select {:id "language-selector"
                     :default-value (name @i18n/!preferred-language)
                     :on-change (fn [event]
                                  (reset! i18n/!preferred-language
                                          (-> event .-target .-value keyword)))}]
           (map (fn [lang] [:option {:value (name lang)}
                            (get-in i18n/dict [lang :meta/lect])]))
           (keys i18n/dict))])

(ui/defview menubar:account [{[route-id] :route}]
  (if (db/get :env/account)
    [:a
     {:href (routes/path-for :auth/logout)
      :class [ui/c:dark-button
              "text-sm px-3 py-1"]}
     (tr :tr/logout)]
    [:a
     {:href (routes/path-for :auth/sign-in)
      :class [ui/c:dark-button
              "text-sm m-1 h-8 p-3"]}
     (tr :tr/sign-in)]))

(ui/defview menubar [params]
  [:div.flex.flex-row.bg-zinc-100.w-full.items-center.h-10.px-2.z-50.relative
   [menubar:lang]
   [:div.flex-grow]
   [menubar:account params]
   [:div.rough-divider]])

(defn http-ok? [rsp]
  (= 200 (.-status rsp)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def input (v/from-element :input
             {:class ["flex h-10 w-full rounded-md border border-input bg-transparent px-3 py-2"
                      "text-sm ring-offset-background placeholder:text-muted-foreground"
                      "focous-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                      "disabled:cursor-not-allowed disabled:opacity-50"]}))

(defn show-messages [field]
  (when-let [messages (forms/visible-messages field)]
    (into [:div.mb-3]
          (map (fn [{:keys [content type]}]
                 [:div.px-3.text-xs {:class (case type
                                              :invalid "text-red-500"
                                              "text-gray-500")} content]))
          messages)))

(ui/defview login-form []
  (ui/with-form [!form {:account/email (?email :init "")
                        :account/password (?password :init "")}
                 :validators {?email (fn [v _]
                                       (when-not (re-find #"^[^@]+@[^@]+$" v)
                                         (tr :tr/invalid-email)))}
                 :required [?email ?password]]
    (let [!step (hooks/use-state :email)]
      [:form.p-4.flex-grow.m-auto.gap-6.flex.flex-col.max-w-md
       {:on-submit (fn [^js e]
                     (.preventDefault e)
                     (case @!step
                       :email (reset! !step :password)
                       :password (prn :submit @!form)))}
       [:h1.text-3xl.font-medium.mb-4.text-center (tr :tr/welcome)]
       [:div.flex.flex-col.gap-2
        [input {:type "email"
                :value @?email
                :placeholder (tr :tr/email)
                :on-change (forms/change-handler ?email)}]
        (show-messages ?email)
        (when (= :password @!step)
          [:<>
           [input {:type "password"
                   :id (str ::password)
                   :value @?password
                   :placeholder (tr :tr/password)
                   :on-change (forms/change-handler ?password)}]
           (str (forms/visible-messages ?password))])
        (str (forms/visible-messages !form))
        [:button
         {:class ["w-full h-10 text-sm p-3"
                  ui/c:dark-button]
          :on-click (fn []
                      (forms/touch! !form)
                      (prn (forms/submittable? !form))
                      (reset! !step :password)
                      (js/setTimeout #(.focus (js/document.getElementById (str ::password)))
                                     100)
                      #_(p/let [res (routes/mutate!
                                     {:route [:login]
                                      :response-fn (fn [^js/Response res _url]
                                                     (when (http-ok? res)
                                                       ;; TODO set this based on response body
                                                       ;; instead (I don't want to wrestle with
                                                       ;; readablestream right now --DAL
                                                       ;; 2023-03-15)
                                                       ;; TODO ...or promise-style with `p/->>`
                                                       (comment
                                                        ;; transact account...
                                                        (db/transact! [(assoc foo :db/id :env/account)])
                                                        )
                                                       #_(reset! !account {:identity @?mbr-name})
                                                       (routes/set-path! [:org/index]))
                                                     res)}
                                     @!mbr)]
                          (prn :logged-in res :mbr @!mbr)
                          #_(reset! !account {:identity @?mbr-name})
                          ))}
         (tr :tr/sign-in)]]

       [:div.relative
        [:div.absolute.inset-0.flex.items-center [:span.w-full.border-t]]
        [:div.relative.flex.justify-center.text-xs.uppercase
         [:span.bg-background.px-2.text-muted-foreground
          (tr :tr/or)]]]
       [ui/a:light-button {:class "w-full h-10 text-zinc-500 text-sm"
                           :href (routes/path-for :oauth2.google/launch)}
        [:img.w-5.h-5.m-2 {:src "/images/google.svg"}] (tr :tr/sign-in-with-google)]
       [:p.px-8.text-center.text-sm.text-muted-foreground (tr :tr/sign-up-agree-to)
        [:a.gray-link {:href "/terms"} (tr :tr/tos)]
        (tr :tr/and)
        [:a.gray-link {:href "/privacy"} (tr :tr/pp)] "."]])))

(ui/defview login-screen [params]
  [:div.grid.md:grid-cols-2.gap-4.divide-x-4.divide-solid
   ;; left column
   [:div.hidden.md:block.bg-zinc-300.text-white.p-5.text-2xl.bg-fit.bg-no-repeat.bg-center
    {:style {:background-color "white"
             :background-image (ui/css-url "https://media.discordapp.net/attachments/999411359253004318/1098565546976493588/mhuebert_creative_loft_wild_colors_computering_tinkering_contra_3e62c9fc-82dd-4c93-8bce-07efeace1a77.png?width=650&height=650")}}
    "Sparkboard"]
   ;; right column
   [:div.flex.flex-col.h-screen.border-left.border-gray-200.border-8s
    [:div.flex.flex-grow
     [login-form]]
    [:div.p-4.flex.justify-end
     [menubar:lang]]]])

(ui/defview org-index [params]
  [:<>
   [menubar params]
   [:div.pa3
    [:h2 (tr :tr/orgs)]
    [:section#orgs-grid
     (into [:div.grid.grid-cols-4.gap-4]
           (map (fn [org]
                  [:div.shadow.p-3
                   ;; use radix-ui to make a dropdown button that includes a delete button
                   [:div.rough-icon-button
                    {:on-click #(when (js/window.confirm (str "Really delete organization "
                                                              (:org/title org) "?"))
                                  (routes/mutate! {:route [:org/delete]} (:org/id org)))}
                    "X"]

                   [:a {:href (routes/path-for :org/one org)}
                    (:org/title org)]
                   ]))
           (ws/use-query! :org/index))]
    [:section#add-org
     [(routes/use-view :org/create)]]]])

(ui/defview home [params]
  (if (db/get :env/account)
    [org-index params]
    [login-screen params]))

(ui/defview org-create [params]
  (with-form [!org {:org/title ?title}]
    [:div
     [:h2 (tr :tr/new) " " (tr :tr/org)]
     [:form
      [:label "Title"]
      [input {:type "text"
              :value (or @?title "")
              :on-change (forms/change-handler ?title)}]
      [:button
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
       (str (tr :tr/create) " " (tr :tr/org))]]]))

(ui/defview board:create [{:as params :keys [route org/id]}]
  (let [[n n!] (use-state "")]
    [:div
     [:h3 (tr :tr/new) " " (tr :tr/board)]
     [input {:placeholder "Board title"
             :value n
             :on-input #(n! (-> % .-target .-value))}]
     [:button {:on-click #(routes/mutate! {:route route
                                           :response-fn (fn [^js/Response res _url]
                                                          (when (http-ok? res)
                                                            (routes/set-path! [:org/one {:org/id (:org/id params)}])
                                                            ;; FIXME "Uncaught (in promise) DOMException: The operation was aborted."
                                                            res))}
                                          {:board/title n})}
      (str (tr :tr/create) " " (tr :tr/board))]]))

(ui/defview project:create [{:as params :keys [route board/id]}]
  (let [[n n!] (use-state "")]
    [:div
     [:h3 (str (tr :tr/new) " " (tr :tr/project))]
     [input {:placeholder "Project title"
             :value n
             :on-input #(n! (-> % .-target .-value))}]
     [:button {:on-click #(routes/mutate! {:route route
                                           :response-fn (fn [^js/Response res _url]
                                                          (when (http-ok? res)
                                                            (routes/set-path! [:board/one {:board/id (:board/id params)}])
                                                            res))}
                                          {:project/title n})}
      (str (tr :tr/create) " " (tr :tr/project))]]))

(ui/defview member:create [{:as params :keys [route board/id]}]
  (let [new-mbr (use-state {:member/name "", :member/password ""})]
    [:div
     [:h3 (str (tr :tr/new) " " (tr :tr/member))]
     [input {:placeholder "Member name"
             :value (:member/name @new-mbr)
             :on-input #(swap! new-mbr assoc :member/name (-> % .-target .-value))}]
     [input {:placeholder "Member password"
             :type "password"
             :value (:member/password @new-mbr)
             :on-input #(swap! new-mbr assoc :member/password (-> % .-target .-value))}]
     [:button {:on-click #(routes/mutate! {:route route
                                           :response-fn (fn [^js/Response res _url]
                                                          (when (http-ok? res)
                                                            (routes/set-path! [:board/one {:board/id (:board/id params)}])
                                                            res))}
                                          @new-mbr)}
      (str (tr :tr/create) " " (tr :tr/member))]]))

(ui/defview org:one [{:as params :keys [org/id query-params]}]
  (let [value (ws/use-query! [:org/one {:org/id id}])
        [query-params set-query-params!] (use-state query-params)
        search-result (ws/use-query! [:org/search {:org/id id
                                                   :query-params query-params}])
        [pending? start-transition] (react/useTransition)]
    [:div
     [:h1 (tr :tr/org) " " (:org/title value)]
     [:a {:href (routes/path-for :board/create params)}
      (tr :tr/new) " " (tr :tr/board)]
     [:p (-> value :entity/domain :domain/name)]
     (let [[q set-q!] (yawn.hooks/use-state-with-deps (:q query-params) (:q query-params))]
       [:section
        [:h3 (tr :tr/search)]
        [input
         {:id "org-search"
          :placeholder (tr :tr/search-across-org)
          :type "search"
          :on-input (fn [event] (-> event .-target .-value set-q!))
          :on-key-down (j/fn [^js {:keys [key]}]
                         (when (= key "Enter")
                           (start-transition
                            #(-> {:q (when (<= 3 (count q)) q)}
                                 routes/merge-query!
                                 set-query-params!))))
          :value (or q "")}]
        (when pending? [:div "Loading..."])
        (into [:ul]
              (map (comp (partial vector :li)
                         str))
              search-result)])
     [:section [:h3 (tr :tr/boards)]
      (into [:ul]
            (map (fn [board]
                   [:li [:a {:href (routes/path-for :board/one board)} ;; path-for knows which key it wants (:board/id)
                         (:board/title board)]]))
            (:board/_org value))]]))

(ui/defview board:one [{:as b :board/keys [id]}]
  (let [value (ws/use-query! [:board/one {:board/id id}])]
    [:<>
     [:h1 (str (tr :tr/board) (:board/title value))]
     [:p (-> value :entity/domain :domain/name)]
     [:blockquote
      [safe-html (-> value
                     :board/description
                     :text-content/string)]]
     [:div.rough-tabs {:class "w-100"}
      [:div.rough-tab {:name (tr :tr/projects)
                       :class "db"}
       [:a {:href (routes/path-for :project/create b)}
        (tr :tr/new) " " (tr :tr/project)]
       (into [:ul]
             (map (fn [proj]
                    [:li [:a {:href (routes/path-for :project/one proj)}
                          (:project/title proj)]]))
             (:project/_board value))]
      [:div.rough-tab {:name (tr :tr/members)
                       :class "db"}
       [:a {:href (routes/path-for :member/create b)}
        (tr :tr/new) " " (tr :tr/member)]
       (into [:ul]
             (map (fn [member]
                    [:li
                     [:a {:href (routes/path-for :member/one member)}
                      (:member/name member)]]))
             (:member/_board value))]
      [:div.rough-tab {:name "I18n" ;; FIXME any spaces in the tab name cause content to break; I suspect a bug in `with-props`. DAL 2023-01-25
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
    :video/youtube-id (youtube-embed v)
    :video/youtube-url [:a {:href v} "youtube video"]
    :video/vimeo-url [:a {:href v} "vimeo video"]
    {kind v}))

(comment
 (video-field [:field.video/youtube-sdgurl "gMpYX2oev0M"])
 )

(ui/defview project:one [{:as p :project/keys [id]}]
  (let [value (ws/use-query! [:project/one {:project/id id}])]
    [:div
     [:h1 (str (tr :tr/project) " " (:project/title value))]
     [:blockquote (:project/summary-text value)]
     (when-let [badges (:project/badges value)]
       [:section [:h3 (tr :tr/badges)]
        (into [:ul]
              (map (fn [bdg] [:li (:badge/label bdg)]))
              badges)])
     (when-let [vid (:project/video value)]
       [:section [:h3 (tr :tr/video)]
        [video-field vid]])]))

(ui/defview member:one [{:as mbr :member/keys [id]}]
  (let [value (ws/use-query! [:member/one {:member/id id}])]
    [:div
     [:h1 (str/join " " [(tr :tr/member) (:member/name value)])]
     (when-let [tags (seq (concat (:member/tags value)
                                  (:member/tags.custom value)))]
       [:section [:h3 (tr :tr/tags)]
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

(ui/defview show-query [[id :as route]]
  (let [value (ws/use-query! route)
        value-str (yawn.hooks/use-memo
                   (fn [] (with-out-str (pprint value)))
                   (yawn.hooks/use-deps value))]
    [:pre value-str]))

(ui/defview drawer [{:keys [initial-height]} child]
  ;; the divider is draggable and sets the height of the drawer
  (let [!height (yawn.hooks/use-state initial-height)]
    [:Suspense {:fallback "Loading..."}
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

(ui/defview dev-drawer [{:as match :keys [route]}]
  [drawer {:initial-height 100}
   [:Suspense {:fallback (v/x "Hi")}
    (into [:p.text-lg]
          (for [[id :as route] (routes/breadcrumb (db/get :env/location :path))]
            [:a.mr-3.rounded.bg-black.text-white.px-2..no-underline.inline-block
             {:href (routes/path-for route)} (str id)]))
    (str route)
    [show-query route]]])
