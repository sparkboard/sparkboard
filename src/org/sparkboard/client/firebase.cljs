(ns org.sparkboard.client.firebase
  (:require ["firebase/app" :as firebase]
            ["firebase/auth"]
            ["react" :as react]
            [applied-science.js-interop :as j]
            [kitchen-async.promise :as p]
            [org.sparkboard.env :as env]
            [triple.view.react.experimental.atom :as ratom]
            [org.sparkboard.client.loaders :as loaders]))

(defonce app firebase)
(defonce auth (delay (.auth firebase)))
(goog/exportSymbol "firebase" app)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; auth state

(defonce current-user (ratom/create :initial-state))

(defn id-token []
  (let [user @current-user]
    (when (object? user)
      (j/get user :idToken))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; firebaseui-web

(def ui-config
  (j/lit {:signInOptions [(j/get-in app [:auth :GoogleAuthProvider :PROVIDER_ID])]}))

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
  (j/call app :initializeApp (.parse js/JSON (env/get :firebase/config)))

  (j/call @auth :onAuthStateChanged
          (fn [user]
            (if (nil? user)
              (reset! current-user :signed-out)
              (p/let [token (j/call user :getIdToken)]
                (reset! current-user
                        (j/extend! (j/lit {:idToken token}) user)))))))