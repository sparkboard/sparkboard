(ns org.sparkboard.client
  (:require ["react" :as react]
            ["react-dom" :as react-dom]
            [org.sparkboard.client.firebase :as firebase]
            [org.sparkboard.client.auth :as auth]
            [triple.view.react.hooks :as hooks]
            [triple.view :as v]))

(def LOCALE :en)

(v/defview root []
  [:div.pa4
   [auth/auth-header {:locale LOCALE}]])

(defn render []
  (react-dom/render (v/to-element [root]) (js/document.getElementById "web")))

(defn init []

  (firebase/init)

  (when (exists? js/ReactRefresh)
    (.injectIntoGlobalHook js/ReactRefresh goog/global))

  (render))