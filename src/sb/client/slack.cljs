(ns sb.client.slack
  (:require ["firebase/database"]
            [applied-science.js-interop :as j]
            [clojure.string :as str]
            [lambdaisland.uri :as uri]
            [re-db.api :as db]
            [org.sparkboard.slack.firebase :as firebase]
            [yawn.hooks :as hooks :refer [use-deref]]
            [yawn.view :as v]
            [sb.app.views.ui :as ui]))

(def db (delay (.database firebase/app)))

(defn use-firebase-value [segments]
  (let [path (some->> segments (map name) (str/join "/"))
        [value set-value!] (hooks/use-state nil)]
    (hooks/use-effect
      (fn []
        (when segments
          (let [ref (j/call @db :ref path)
                cb  (j/call ref :on "value" (fn [snap] (set-value! (j/call snap :val))))]
            #(j/call ref :off "value" cb))))
      [path])
    value))

(def section :section.center.mw6.pa3.sans-serif.lh-copy)
(def button :a.br3.bg-blue.pa3.white.no-underline.b.mv3.db.tc)

(ui/defview invite-offer
  {:route "/slack/invite-offer"}
  [{{:keys [custom-token
            team-id
            invite-link
            team-domain
            team-name
            redirect-encoded]} :query-params}]
  (j/let [^:js {:keys [uid displayName photoURL email]} (:user (use-deref firebase/!auth-state))
          slack-user (use-firebase-value (when uid [:account uid :slack-team team-id :user-id]))
          team-link  (str "https://" team-domain ".slack.com")
          app-id     (db/get :env/config :slack/app-id)
          redirect   (js/decodeURIComponent redirect-encoded)]
    (hooks/use-effect
      #(when custom-token (j/call @firebase/auth :signInWithCustomToken custom-token) nil))
    [section
     (if (nil? uid)
       "Loading..."
       [:<>
        (when uid
          [:div.sans-serif.lh-copy
           [:div.flex.items-center.f4
            (when photoURL
              [:img.br3.w3.mr3 {:src photoURL}])
            [:div.mr3 "Hello, " displayName "."
             [:div.f6.gray email]]]])
        (if slack-user
          [:p
           "Thank you for joining!"
           [button {:href redirect} "Continue"]]
          [:div
           [:p
            "You are invited to join " [:b team-name] " on Slack."]
           [:div.tc
            [button {:href invite-link :target "_blank"} "Accept Invitation"]]

           [:p "Already a member of "
            [:a.b.black.no-underline {:href team-link} team-domain ".slack.com"]
            "? Visit the "
            [:a {:href (str team-link "/app_redirect?" (uri/map->query-string {:team team-id
                                                                               :app  app-id
                                                                               :tab  "home"}))}
             "Sparkboard home tab"] " to link your account so that you can use Slack and Sparkboard together."]])])]))

(ui/defview link-complete
  {:route "/slack/link-complete"}
  [{{:keys [slack sparkboard]} :query-params}]
  [section
   [:h1.tc "Thanks!"]
   [:p.tc "You may now return to our " [:a {:href slack} "Slack"] " or " [:a {:href sparkboard} "Sparkboard"] "."]])
