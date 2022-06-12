(ns org.sparkboard.client.auth
  (:require ["react" :as react]
            [applied-science.js-interop :as j]
            [org.sparkboard.client.firebase :as firebase]
            [org.sparkboard.util.promise :as p]
            [triple.view :as v]))

(v/defview after-promise [{:keys [fallback promise]} element]
  (let [mounted-ref (react/useRef true)
        [done? done!] (react/useState nil)]
    (react/useEffect (fn []
                       (p/do promise
                             (when (true? (j/get mounted-ref :current))
                               (done! true)))
                       (fn [] (j/assoc! mounted-ref :current false))))
    (if done?
      element
      fallback)))

(v/defview use-firebaseui-web []
  (react/useEffect
    (fn []
      (j/call @firebase/UI :start "#firebaseui" firebase/ui-config)
      js/undefined))
  [:div#firebaseui])

(v/defview auth-header* []
  (let [{:keys [status id-token user]} @firebase/auth-state]
    (cond (nil? status) "Loading..."
          (or (= :signed-out status)
              (j/call @firebase/UI :isPendingRedirect)) [use-firebaseui-web]

          (= :signed-in status)
          (j/let [^:js {:keys [displayName photoURL]} user]
            [:div.sans-serif.lh-copy
             [:div.flex.items-center.f4
              (when photoURL
                [:img.br3.w3.mr3 {:src photoURL}])
              [:div.mr3 "Hello, " displayName ". "]
              [:div.flex-auto]
              [:a.blue.pointer.underline-hover {:on-click #(j/call @firebase/auth :signOut)} "Sign Out"]]
             [:div.f6.mt3 [:b "id token: "] id-token]]))))

(v/defview auth-header [{:keys [locale]
                         :or {locale :en}}]
  [after-promise {:promise (firebase/ui-deps locale)
                  :fallback "Loading.."}
   [auth-header*]])
