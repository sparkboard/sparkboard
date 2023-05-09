(ns sparkboard.client.views
  (:require
   ["react" :as react]
   ["@radix-ui/react-dropdown-menu" :as dm]
   [applied-science.js-interop :as j]
   [clojure.pprint :refer [pprint]]
   [inside-out.forms :as forms :refer [with-form]]
   [re-db.api :as db]
   [sparkboard.client.sanitize :refer [safe-html]]
   [sparkboard.i18n :as i18n :refer [tr]]
   [sparkboard.routes :as routes]
   [sparkboard.views.ui :as ui]
   [sparkboard.websockets :as ws]
   [promesa.core :as p]
   [yawn.hooks :as hooks :refer [use-state]]
   [yawn.view :as v]
   [inside-out.forms :as forms]))

;; TODO
;; - separate register screen
;; - password signin:
;;   - new-account flow, reset-pass, verify new email, check-pass-start-session

(ui/defview menubar:lang []
  [:div.flex.flex-row
   [:el dm/Root
    [:el.btn.btn-transp.h-7 dm/Trigger
     [:svg {:class "h-5 w-5" :xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :strokeWidth "{1.5}" :stroke "currentColor"}
      [:path {:strokeLinecap "round" :strokeLinejoin "round" :d "M10.5 21l5.25-11.25L21 21m-9-3h7.5M3 5.621a48.474 48.474 0 016-.371m0 0c1.12 0 2.233.038 3.334.114M9 5.25V3m3.334 2.364C11.176 10.658 7.69 15.08 3 17.502m9.334-12.138c.896.061 1.785.147 2.666.257m-4.589 8.495a18.023 18.023 0 01-3.827-5.802"}]]]
    [:el dm/Portal
     [:el.bg-white dm/Content {:sideOffset 0 :collision-padding 16}
      (into [:el.shadow.rounded.text-sm dm/RadioGroup
             {:value @i18n/!selected-locale
              :on-value-change (fn [v]
                                 ;; TODO loading/error state
                                 (p/do (routes/POST :account/set-locale v)
                                       (js/window.location.reload)))}]
            (map (fn [lang] [:el.text-left.flex.pr-4.py-2.cursor-pointer.hover:outline-none.hover:bg-zinc-50
                             dm/RadioItem {:class "data-[disabled]:text-gray-500"
                                           :value lang
                                           :disabled (= lang @i18n/!selected-locale)}
                             [:div.w-6.text-center
                              [:el dm/ItemIndicator "•"]]
                             (get-in i18n/dict [lang :meta/lect])]))
            (keys i18n/dict))]]]])

(ui/defview menubar:account [{[route-id] :route}]
  (if (db/get :env/account)
    [:a.btn.btn-transp.text-sm.px-3.py-1.h-7
     {:href (routes/path-for :account/logout)} :tr/logout]
    [:a.btn.btn-transp.text-sm.px-3.py-1.h-7
     {:href (routes/path-for :account/sign-in)} :tr/sign-in]))

(ui/defview menubar [params]
  [:div.flex.flex-row.bg-zinc-100.w-full.items-center.h-10.px-2.z-50.relative
   [:img.w-5.h-5 {:src ui/logo-url}]
   [:div.flex-grow]
   [menubar:lang]
   [menubar:account params]
   [:div.rough-divider]])

(defn http-ok? [rsp]
  (= 200 (.-status rsp)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn show-messages [field]
  (when-let [messages (forms/visible-messages field)]
    (into [:div.mb-3]
          (map (fn [{:keys [content type]}]
                 [:div.px-3.text-xs {:class (case type
                                              :invalid "text-red-500"
                                              "text-gray-500")} content]))
          messages)))

(defn account:sign-in-with-google []
  (ui/x
   [:a.btn.btn-light
    {:class "w-full h-10 text-zinc-500 text-sm"
     :href (routes/path-for :oauth2.google/launch)}
    [:img.w-5.h-5.m-2 {:src "/images/google.svg"}] :tr/sign-in-with-google]))

(defn account:sign-in-terms []
  (ui/x
   [:p.px-8.text-center.text-sm.text-muted-foreground :tr/sign-in-agree-to
    [:a.gray-link {:href "/documents/terms-of-service"} :tr/tos] ","
    [:a.gray-link {:target "_blank"
                   :href "https://www.iubenda.com/privacy-policy/7930385/cookie-policy"} :tr/cookie-policy]
    :tr/and
    [:a.gray-link {:target "_blank"
                   :href "https://www.iubenda.com/privacy-policy/7930385"} :tr/privacy-policy] "."]))

(comment
 (p/-> (routes/POST :account/sign-in {:account/email ""
                                      :account/password "123123123"})
       js/console.log))

(ui/defview account:sign-in-form [{:keys [route]}]
  (ui/with-form [!account {:account/email (?email :init "")
                           :account/password (?password :init "")}
                 :required [?email ?password]]
    (let [!step (hooks/use-state :email)]
      [:form.p-4.flex-grow.m-auto.gap-6.flex.flex-col.max-w-md
       {:on-submit (fn [^js e]
                     (.preventDefault e)
                     (case @!step
                       :email (do (reset! !step :password)
                                  (js/setTimeout #(.focus (js/document.getElementById "account-password")) 100))
                       :password (p/let [res (routes/POST :account/sign-in @!account)]
                                   (js/console.log "res" res)
                                   (prn :res res))))}
       [:h1.text-3xl.font-medium.mb-4.text-center :tr/welcome]

       [:div.flex.flex-col.gap-2
        (ui/show-field ?email)
        (when (= :password @!step)
          (ui/show-field ?password {:id "account-password"}))
        (str (forms/visible-messages !account))
        [:button.btn.btn-dark.w-full.h-10.text-sm.p-3
         :tr/sign-in]]

       [:div.relative
        [:div.absolute.inset-0.flex.items-center [:span.w-full.border-t]]
        [:div.relative.flex.justify-center.text-xs.uppercase
         [:span.bg-background.px-2.text-muted-foreground :tr/or]]]
       [account:sign-in-with-google]
       [account:sign-in-terms]])))

(ui/defview account:sign-in [params]
  [:div.grid.md:grid-cols-2
   ;; left column
   [:div.hidden.md:block.text-2xl.bg-no-repeat
    {:class ["bg-blend-darken bg-zinc-50 bg-center"]
     :style {:background-size "100px"
             :background-image (ui/css-url ui/logo-url)}}]
   ;; right column
   [:div.flex.flex-col.h-screen.shadow-sm.relative
    [:div.flex.flex-grow
     [account:sign-in-form params]]
    [:div.p-4.flex.justify-end
     [menubar:lang]]]])

(ui/defview org:index [params]
  [:<>
   [menubar params]
   ;; TODO
   ;; New Org button
   ;; format cards (show background image and logo)
   ;; :org/new view
   ;; :org/settings view
   [:div.p-6
    [:div.flex.mb-4
     [:h2 :tr/orgs]
     [:div.flex-grow]
     [:div.btn.btn-light.p-2 {:on-click #(routes/set-path! :org/new)} "New"]]
    [:section#orgs-grid
     (into [:div.grid.grid-cols-4.gap-6]
           (map (fn [org]
                  [:div.shadow.p-3
                   ;; todo: use radix-ui to make a dropdown button that includes a delete button
                   [:div.rough-icon-button
                    {:on-click #(when (js/window.confirm (str "Really delete organization "
                                                              (:org/title org) "?"))
                                  (routes/POST :org/delete {:org/id (:org/id org)}))}
                    "X"]

                   [:a {:href (routes/path-for :org/view org)}
                    (:org/title org)]
                   ]))
           (ws/use-query! :org/index))]]])

(ui/defview home [params]
  (if (db/get :env/account)
    [org:index params]
    [account:sign-in params]))

(defn domain-valid-chars [v _]
  (when (and v (not (re-matches #"^[a-z0-9-]+$" v)))
    (forms/message :invalid
      (tr :tr/invalid-domain)
      {:visibility :always})))

(defn domain-availability-validator []
  (-> (fn [v _]
        (when (>= (count v) 3)
          (p/let [res (routes/GET :domain-availability :query {:domain v})]
            (if (:available? res)
              (forms/message :info
                [:span.text-green-500.font-bold (tr :tr/available)])
              (forms/message :invalid
                (tr :tr/not-available)
                {:visibility :always})))))
      (forms/debounce 1000)))

(comment
 (routes/set-path! :org/new)
 (routes/set-path! :org/index)
 (routes/set-path! :org/view {:org/id "645a2f3e-0c80-404d-b604-db485a39e431"}))

(ui/defview org:new [params]
  (with-form [!org {:org/title ?title
                    :entity/domain {:domain/name ?domain}}
              :required [?title ?domain]
              :validators {?domain [(forms/min-length 3)
                                    domain-valid-chars
                                    (domain-availability-validator)]}]
    [:form.flex.flex-col.gap-3.p-6
     {:on-submit (fn [e]
                   (j/call e :preventDefault)
                   (forms/try-submit+ !org
                     (p/let [result (routes/POST :org/new @!org)]
                       (when-not (:error result)
                         (routes/set-path! :org/view result)))))}
     [:h2.font-bold :tr/new-org]
     (ui/show-field ?title {:placeholder :tr/organization-name})
     (ui/show-field ?domain {:placeholder :tr/subdomain})
     [:div.text-gray-600.text-sm [:span.font-bold (or @?domain (str "< " :tr/subdomain " >"))] ".sparkboard.com"]
     (into [:<>] (map ui/view-message (forms/visible-messages !org)))
     [:button.btn.btn-dark.px-6.py-3.self-start {:type "submit"
                                                 :disabled (not (forms/submittable? !org))}
      :tr/create]]))

(ui/defview board:new [{:as params :keys [route]}]
  (ui/with-form [!board {:board/title ?title}]
    [:div
     [:h3 :tr/new-board]
     (ui/show-field ?title)
     [:button {:on-click #(p/let [res (routes/POST route @!board '[*])]
                            (when-not (:error res)
                              (routes/set-path! [:org/view {:org/id (:org/id params)}])
                              ;; FIXME "Uncaught (in promise) DOMException: The operation was aborted."
                              ))}
      :tr/create]]))

(ui/defview project:new [{:as params :keys [route board/id]}]
  (ui/with-form [!project {:project/title ?title}]
    [:div
     [:h3 :tr/new-project]
     (ui/show-field ?title)
     [:button {:on-click #(p/let [res (routes/POST route @!project)]
                            (when-not (:error res)
                              (routes/set-path! [:board/view {:board/id (:board/id params)}])))}
      :tr/create]]))

(ui/defview board:register [{:as params :keys [route board/id]}]
  (ui/with-form [!member {:member/name ?name :member/password ?pass}]
    [:div
     [:h3 :tr/register]
     (ui/show-field ?name)
     (ui/show-field ?pass)
     [:button {:on-click #(p/let [res (routes/POST route @!member)]
                            (when (http-ok? res)
                              (routes/set-path! [:board/view {:board/id (:board/id params)}])
                              res))}
      :tr/register]]))

(ui/defview org:view [{:as params :keys [org/id query-params]}]
  (let [value (ws/use-query! [:org/view {:org/id id}])
        [query-params set-query-params!] (use-state query-params)
        search-result (ws/use-query! [:org/search {:org/id id
                                                   :query-params query-params}])
        [pending? start-transition] (react/useTransition)]
    [:div
     [:h1 [:a {:href (routes/path-for :org/view {:org/id id})} (:org/title value)]]
     [:a {:href (routes/path-for :board/new params)} :tr/new-board]
     [:p (-> value :entity/domain :domain/name)]
     (let [[q set-q!] (yawn.hooks/use-state-with-deps (:q query-params) (:q query-params))]
       [:section
        [:h3 :tr/search]
        [:input.form-input
         {:id "org-search"
          :placeholder :tr/search-across-org
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
     [:section [:h3 :tr/boards]
      (into [:ul]
            (map (fn [board]
                   [:li [:a {:href (routes/path-for :board/view board)} ;; path-for knows which key it wants (:board/id)
                         (:board/title board)]]))
            (:board/_org value))]]))

(ui/defview board:view [{:as b :board/keys [id]}]
  (let [value (ws/use-query! [:board/view {:board/id id}])]
    [:<>
     [:h1 (:board/title value)]
     [:p (-> value :entity/domain :domain/name)]
     [:blockquote
      [safe-html (-> value
                     :board/description
                     :text-content/string)]]
     ;; TODO - tabs
     [:div.rough-tabs {:class "w-100"}
      [:div.rough-tab ;; projects
       [:a {:href (routes/path-for :project/new b)} :tr/new-project]
       (into [:ul]
             (map (fn [proj]
                    [:li [:a {:href (routes/path-for :project/view proj)}
                          (:project/title proj)]]))
             (:project/_board value))]
      [:div.rough-tab ;; members
       [:a {:href (routes/path-for :board/register b)} :tr/new-member]
       (into [:ul]
             (map (fn [member]
                    [:li
                     [:a {:href (routes/path-for :member/view member)}
                      (:member/name member)]]))
             (:member/_board value))]
      [:div.rough-tab {:name "I18n" ;; FIXME any spaces in the tab name cause content to break; I suspect a bug in `with-props`. DAL 2023-01-25
                       :class "db"}
       [:ul ;; i18n stuff
        [:li "suggested locales:" (str (:i18n/locale-suggestions value))]
        [:li "default locale:" (str (:i18n/default-locale value))]
        [:li "extra-translations:" (str (:i18n/locale-dicts value))]]]]]))

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

(ui/defview project:view [{:as p :project/keys [id]}]
  (let [value (ws/use-query! [:project/view {:project/id id}])]
    [:div
     [:h1 (:project/title value)]
     [:blockquote (:project/summary-text value)]
     (when-let [badges (:project/badges value)]
       [:section [:h3 :tr/badges]
        (into [:ul]
              (map (fn [bdg] [:li (:badge/label bdg)]))
              badges)])
     (when-let [vid (:project/video value)]
       [:section [:h3 :tr/video]
        [video-field vid]])]))

(ui/defview member:view [{:as mbr :member/keys [id]}]
  (let [value (ws/use-query! [:member/view {:member/id id}])]
    [:div
     [:h1 (:member/name value)]
     (when-let [tags (seq (concat (:member/tags value)
                                  (:member/tags.custom value)))]
       [:section [:h3 :tr/tags]
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
