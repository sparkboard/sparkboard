(ns sb.client.auth
  (:require ["react" :as react]
            [applied-science.js-interop :as j]
            [org.sparkboard.slack.firebase :as firebase]
            [promesa.core :as p]
            [sb.app.views.ui :as ui]
            [yawn.hooks :refer [use-deref]]))

;; TODO
;; https://firebase.google.com/docs/auth/web/redirect-best-practices
;; temporary fix was using "popup" but this has its own drawbacks

(ui/defview after-promise [{:keys [fallback promise]} element]
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

(ui/defview use-firebaseui-web []
  (react/useEffect
   (fn []
     (j/call @firebase/UI :start "#firebaseui" firebase/ui-config)
     js/undefined))
  [:div#firebaseui])

(ui/defview auth-header* []
  (let [{:keys [status id-token user]} (use-deref firebase/!auth-state)]
    (cond (nil? status) "Loading..."
          (or (= :signed-out status)
              (j/call @firebase/UI :isPendingRedirect)) [use-firebaseui-web]

          (= :signed-in status)
          (j/let [^:js {:keys [displayName photoURL]} user]
            [:div.sans-serif.lh-copy
             [:div.flex.items-center.f4
              (when photoURL
                [:img.br3.w3.mr3 {:src photoURL
                                  :referrerpolicy "no-referrer"}])
              [:div.mr3 "Hello, " displayName ". "]
              [:div.flex-auto]
              [:a.blue.pointer.underline-hover {:on-click #(j/call @firebase/auth :signOut)} "Sign Out"]]
             [:div.f6.mt3 [:b "id token: "] id-token]]))))
