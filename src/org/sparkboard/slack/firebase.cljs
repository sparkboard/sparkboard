(ns org.sparkboard.slack.firebase
  (:require ["firebase/app" :as firebase]
            ["firebase/auth"]
            ["react" :as react]
            [applied-science.js-interop :as j]
            [re-db.api :as db]
            [org.sparkboard.slack.loaders :as loaders]
            [org.sparkboard.slack.promise :as p]))

(defonce app firebase)
(defonce auth (delay (.auth firebase)))
(goog/exportSymbol "firebase" app)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; auth state


(defonce !auth-state (atom nil))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; firebaseui-web

(def ui-config
  (j/lit {:signInOptions [(j/get-in app [:auth :GoogleAuthProvider :PROVIDER_ID])]
          :signInFlow "popup"}))

(defn ui-deps [locale]
  (p/all [(loaders/css! "https://www.gstatic.com/firebasejs/ui/4.5.0/firebase-ui-auth.css")
          (loaders/js! (str "https://www.gstatic.com/firebasejs/ui/3.2.0/firebase-ui-auth__" (name locale) ".js"))]))

(defonce UI
         (delay
           (let [AuthUI (j/get-in js/window [:firebaseui :auth :AuthUI])]
             (AuthUI. @auth))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; initial setup

(defn init []

  (let [firebase-config (clj->js (db/get :env/config :firebase/app-config))]
    (j/call app :initializeApp firebase-config))

  (j/call @auth :onAuthStateChanged
          (fn [user]
            (if (nil? user)
              (reset! !auth-state {:status :signed-out})
              (p/let [token (j/call user :getIdToken)]
                (reset! !auth-state
                        {:status :signed-in
                         :uid (j/get user :uid)
                         :user user
                         :id-token token}))))))
