(ns org.sparkboard.server.auth
  (:require [buddy.auth]
            [ring.middleware.oauth2 :as oauth2]
            [buddy.auth.backends]
            [buddy.auth.middleware]
            [buddy.sign.jwt :as jwt]
            [buddy.core.keys :as buddy.keys]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [org.sparkboard.routes :as routes]
            [org.sparkboard.server.env :as env]))

(def oauth2-config {:google
                    {:client-id (-> env/config :oauth.google/client-id)
                     :client-secret (-> env/config :oauth.google/client-secret)
                     :scopes ["profile openid email"]
                     :authorize-uri "https://accounts.google.com/o/oauth2/v2/auth"
                     :access-token-uri "https://accounts.google.com/o/oauth2/token"
                     :launch-uri (routes/path-for [:oauth2.google/launch])
                     :redirect-uri (routes/path-for [:oauth2.google/callback])
                     :landing-uri (routes/path-for [:oauth2.google/landing])}})

(defn wrap-auth [f]
  (let [session-backend (buddy.auth.backends/session)]
    (-> f
        (buddy.auth.middleware/wrap-authorization session-backend)
        (buddy.auth.middleware/wrap-authentication session-backend)
        (oauth2/wrap-oauth2 oauth2-config))))

(def google-public-key
  (delay (-> "https://www.googleapis.com/oauth2/v3/certs"
             http/get
             :body
             (json/parse-string keyword)
             :keys
             first buddy.keys/jwk->public-key)))

(defn oauth2-google-landing [{:as req :keys [oauth2/access-tokens]} params]
  (let [{:keys [token id-token]} (:google access-tokens)
        url "https://www.googleapis.com/oauth2/v3/userinfo"
        {:as decoded-token
         :keys [email email_verified]} (jwt/unsign id-token @google-public-key {:alg :rs256
                                                                                :iss "accounts.google.com"})
        {:as info :keys [name given_name family_name picture locale]} (-> (http/get url {:headers {"Authorization" (str "Bearer " token)}})
                                                                          :body
                                                                          (json/parse-string keyword))]
    ;; TODO
    ;; set session cookie?
    {:body (str {:email email :email_verified email_verified :name name :picture picture})}))