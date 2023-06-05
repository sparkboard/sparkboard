(ns sparkboard.client.views
  (:require ["@radix-ui/react-dropdown-menu" :as dm]
            [inside-out.forms :as forms]
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.i18n :as i18n]
            [sparkboard.routes :as routes]
            [sparkboard.views.layouts :as layouts]
            [sparkboard.views.ui :as ui]
            [yawn.hooks :as h]
            [yawn.view :as v]))

;; TODO
;; - separate register screen
;; - password signin:
;;   - new-account flow, reset-pass, verify new email, check-pass-start-session

(defn icon:languages [& [classes]]
  (v/x
   [:svg {:xmlns "http://www.w3.org/2000/svg"
          :viewBox "0 0 20 20"
          :fill "currentColor"
          :className classes}
    [:path
     {:d
      "M7.75 2.75a.75.75 0 00-1.5 0v1.258a32.987 32.987 0 00-3.599.278.75.75 0 10.198 1.487A31.545 31.545 0 018.7 5.545 19.381 19.381 0 017 9.56a19.418 19.418 0 01-1.002-2.05.75.75 0 00-1.384.577 20.935 20.935 0 001.492 2.91 19.613 19.613 0 01-3.828 4.154.75.75 0 10.945 1.164A21.116 21.116 0 007 12.331c.095.132.192.262.29.391a.75.75 0 001.194-.91c-.204-.266-.4-.538-.59-.815a20.888 20.888 0 002.333-5.332c.31.031.618.068.924.108a.75.75 0 00.198-1.487 32.832 32.832 0 00-3.599-.278V2.75z"}]
    [:path
     {:fillRule "evenodd"
      :d
      "M13 8a.75.75 0 01.671.415l4.25 8.5a.75.75 0 11-1.342.67L15.787 16h-5.573l-.793 1.585a.75.75 0 11-1.342-.67l4.25-8.5A.75.75 0 0113 8zm2.037 6.5L13 10.427 10.964 14.5h4.073z"
      :clipRule "evenodd"}]]))

(def menu-content-classes (v/classes ["rounded bg-popover text-popover-txt "
                                      "shadow-md ring-1 ring-txt/10"
                                      "focus:outline-none z-50"]))

(def menu-portal (v/from-element :el dm/Portal))
(def menu-content (v/from-element :el dm/Content {:sideOffset        0
                                                  :collision-padding 16
                                                  :class             menu-content-classes}))
(defn menu-item [props & children]
  (let [checks? (contains? props :selected)]
    (v/x [:div (v/props {:class ["block px-4 py-2 text-sm relative"
                                 "data-[selected=true]:font-bold data-[selected=true]:cursor-default"
                                 "cursor-pointer data-[selected=false]:hover:bg-popover-txt/10"
                                 (when checks? "pl-8")]
                         :data-selected (:selected props)}
                        (dissoc props :selected))
          (when checks?
            [:span.absolute.inset-y-0.left-0.flex.items-center.pl-2.text-txt.inline-flex
             {:class "data-[selected=false]:hidden"
              :data-selected (:selected props)}
             [ui/icon:checkmark "h-4 w-4"]])
          children])))
 

(defn lang-menu-content []
  (let [current-locale (i18n/current-locale)
        on-select      (fn [v]
                         (p/do (routes/POST :account/set-locale v)
                               (js/window.location.reload)))]
    (map (fn [lang]
           (let [selected (= lang current-locale)]
             [menu-item {:selected selected
                         :on-click (when-not selected #(on-select lang))}
              (get-in i18n/dict [lang :meta/lect])]))
         (keys i18n/dict))) )

(ui/defview header:lang []
  [:div.flex.flex-row.items-center {:class "hover:text-txt-faded"}
   [:el dm/Root
    [:el.focus-visible:outline-none dm/Trigger
     [icon:languages "w-5 h-5"]]
    [menu-portal 
     [menu-content
      (lang-menu-content)]]]])

(ui/defview header:account []
  (if-let [account (db/get :env/account)]
    [:el dm/Root 
     [:el.focus-visible:outline-none dm/Trigger 
      [:img.rounded-full.h-8.w-8 {:src (ui/asset-src (:account/photo account) :logo)}]]
     [menu-portal 
      [menu-content 
       [menu-item {:on-click #(routes/set-path! :account/logout)} :tr/logout]

       [:el dm/Sub 
        [:el.px-4.py-2 dm/SubTrigger [icon:languages "w-5 h-5"]]
        [menu-portal 
         [:el dm/SubContent {:class menu-content-classes}
          (lang-menu-content)]]]]]]
    [:a.btn.btn-transp.px-3.py-1.h-7
     {:href (routes/path-for :account/sign-in)} :tr/sign-in]))





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
    [:p.px-8.text-center.text-sm {:class "text-txt/70"} :tr/sign-in-agree-to
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
         [:span.bg-back.px-2.text-muted-txt :tr/or]]]
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


