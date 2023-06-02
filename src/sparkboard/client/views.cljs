(ns sparkboard.client.views
  (:require
    ["react" :as react]
    ["@radix-ui/react-dropdown-menu" :as dm]
    [applied-science.js-interop :as j]
    [clojure.pprint :refer [pprint]]
    [clojure.string :as str]
    [inside-out.forms :as forms :refer [with-form]]
    [re-db.api :as db]
    [re-db.reactive :as r]
    [sparkboard.i18n :as i18n :refer [tr]]
    [sparkboard.routes :as routes]
    [sparkboard.util :as u]
    [sparkboard.views.ui :as ui]
    [sparkboard.websockets :as ws]
    [promesa.core :as p]
    [yawn.hooks :as h :refer [use-state]]
    [sparkboard.views.layouts :as layouts]
    [yawn.view :as v]
    [inside-out.forms :as forms]))

;; TODO
;; - separate register screen
;; - password signin:
;;   - new-account flow, reset-pass, verify new email, check-pass-start-session

(ui/defview header:lang []
  [:div.flex.flex-row
   [:el dm/Root
    [:el.btn.btn-transp.h-7 dm/Trigger
     [:svg {:class "h-5 w-5" :xmlns "http://www.w3.org/2000/svg" :fill "none" :viewBox "0 0 24 24" :strokeWidth "{1.5}" :stroke "currentColor"}
      [:path {:strokeLinecap "round" :strokeLinejoin "round" :d "M10.5 21l5.25-11.25L21 21m-9-3h7.5M3 5.621a48.474 48.474 0 016-.371m0 0c1.12 0 2.233.038 3.334.114M9 5.25V3m3.334 2.364C11.176 10.658 7.69 15.08 3 17.502m9.334-12.138c.896.061 1.785.147 2.666.257m-4.589 8.495a18.023 18.023 0 01-3.827-5.802"}]]]
    [:el dm/Portal

     (let [current-locale (i18n/current-locale)
           on-select (fn [v]
                       (p/do (routes/POST :account/set-locale v)
                             (js/window.location.reload)))]
       (into [:el dm/Content {:sideOffset 0
                              :collision-padding 16
                              :class ["rounded bg-popover text-popover-foreground "
                                      "shadow-md ring-1 ring-foreground/10"
                                      "focus:outline-none z-50"]}]
             (map (fn [lang]
                    (let [current? (= lang current-locale)]
                      [:div {:class ["block px-4 py-2 text-sm pr-8 relative"
                                     (if current?
                                       "font-bold cursor-default"
                                       "cursor-pointer hover:bg-popover-foreground/10")]
                             :on-click (when-not current? #(on-select lang))}
                       (get-in i18n/dict [lang :meta/lect])
                       (when current?
                         [:span.absolute.inset-y-0.right-0.flex.items-center.pr-2.text-foreground
                          [ui/icon:checkmark]])]))
                  (keys i18n/dict))))]]])

(ui/defview header:account [{[route-id] :route}]
  (if (db/get :env/account)
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routes/path-for :account/logout)} :tr/logout]
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routes/path-for :account/sign-in)} :tr/sign-in]))

(ui/defview header [params]
  [:div.flex.flex-row.w-full.items-center.h-10.z-50.relative.px-body
   {:class "bg-secondary text-foreground"}

   [:a {:href "/"}
    (ui/logo "w-5 h-5")
    #_[:img.w-5.h-5 {:src ui/logo-url}]]
   [:div.flex-grow]
   [header:lang]
   [header:account params]
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
    (let [!step (h/use-state :email)]
      [:form.flex-grow.m-auto.gap-6.flex.flex-col.max-w-md.p-4
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
        [:button.btn.btn-primary.w-full.h-10.text-sm.p-3
         :tr/sign-in]]

       [:div.relative
        [:div.absolute.inset-0.flex.items-center [:span.w-full.border-t]]
        [:div.relative.flex.justify-center.text-xs.uppercase
         [:span.bg-background.px-2.text-muted-foreground :tr/or]]]
       [account:sign-in-with-google]
       [account:sign-in-terms]])))

(ui/defview ^:no-header account:sign-in [params]
  (layouts/two-col
    [:img.mx-auto {:class "my-6 w-1/4 md:w-1/2"
                   :src ui/logo-url}]
    [:div.p-4.flex.justify-end
     [header:lang]]
    [:div.flex.flex-grow
     [account:sign-in-form params]]))

(ui/defview redirect [to]
  (h/use-effect #(routes/set-path! to)))

(ui/defview home [params]
  (if-let [account-id (db/get :env/account :entity/id)]
    #_[:a.btn.btn-primary.m-10.p-10 {:href (routes/path-for :org/index)} "Org/Index"]
    (redirect (routes/path-for :account/read {:account account-id}))
    (redirect (routes/path-for :account/sign-in params))))

(comment
  (routes/set-path! :org/new)
  (routes/set-path! :org/list)
  (routes/set-path! :org/read {:entity/id "645a2f3e-0c80-404d-b604-db485a39e431"}))


