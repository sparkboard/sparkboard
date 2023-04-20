(ns sparkboard.server.auth
  (:require [buddy.auth]
            [buddy.auth.backends]
            [buddy.auth.middleware]
            [buddy.core.keys :as buddy.keys]
            [buddy.sign.jwt :as jwt]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [sparkboard.transit :as t]
            [sparkboard.routes :as routes]
            [sparkboard.server.env :as env]
            [re-db.api :as db]
            [ring.middleware.cookies :as ring.cookies]
            [ring.middleware.oauth2 :as oauth2]
            [ring.middleware.session :as ring.session]
            [ring.middleware.session.cookie :as ring.session.cookie]
            [ring.util.response :as ring.response]))

;; todo
;; - password sign-in screen (supporting old google accounts)
;; - registration screen
;; - password reset screen

(def oauth2-config {:google
                    {:client-id (-> env/config :oauth.google/client-id)
                     :client-secret (-> env/config :oauth.google/client-secret)
                     :scopes ["profile openid email"]
                     :authorize-uri "https://accounts.google.com/o/oauth2/v2/auth"
                     :access-token-uri "https://accounts.google.com/o/oauth2/token"
                     :launch-uri (routes/path-for [:oauth2.google/launch])
                     :redirect-uri (routes/path-for [:oauth2.google/callback])
                     :landing-uri (routes/path-for [:oauth2.google/landing])}})

(defn wrap-account-lookup [handler]
  (fn [req]
    (handler (if-let [account (try (some-> (get-in req [:cookies "account-id" :value])
                                           t/read)
                                   (catch Exception e nil))]
               (assoc req :account (try (db/pull '[*] account) (catch Exception e nil)))
               req))))

(defn wrap-auth [f]
  (let [session-backend (buddy.auth.backends/session)]
    (-> f
        (wrap-account-lookup)
        (buddy.auth.middleware/wrap-authorization session-backend)
        (buddy.auth.middleware/wrap-authentication session-backend)
        (oauth2/wrap-oauth2 oauth2-config)
        (ring.session/wrap-session {:store (ring.session.cookie/cookie-store
                                            {:key (byte-array (get env/config :webserver.cookie/key (repeatedly 16 (partial rand-int 10))))
                                             :readers {'clj-time/date-time clj-time.coerce/from-string}})
                                    :cookie-attrs {:http-only true :secure (= (env/config :env) "prod")}})
        (ring.cookies/wrap-cookies))))

(def google-public-key
  (delay (-> "https://www.googleapis.com/oauth2/v3/certs"
             http/get
             :body
             (json/parse-string keyword)
             :keys
             first buddy.keys/jwk->public-key)))

(defn logout [_req _params]
  (-> (ring.response/redirect (routes/path-for [:home]))
      (assoc-in [:cookies "account-id"] {:value (t/write nil)
                                         :expires (str (java.util.Date.))})))

(defn keep-vals [m]
  (into {} (filter (comp some? val) m)))

(defn account-tx [account-id provider-info]
  (let [existing (not-empty (db/pull '[*] account-id))
        now (java.util.Date.)]
    [(merge
      ;; backfill account info (do not overwrite current data)
      (keep-vals
       {:ts/created-at now
        :account.provider.google/sub (:sub provider-info)
        :account/email (:email provider-info)
        :account/email-verified? (:email_verified provider-info)
        :account/photo-url (:picture provider-info)
        :account/locale (some-> (:locale provider-info) (str/split #"-") first)})
      existing
      ;; last-sign-in can overwrite existing data
      {:account/last-sign-in now})]))

(defn oauth2-google-landing [{:as req :keys [oauth2/access-tokens]} params]
  (let [{:keys [token id-token]} (:google access-tokens)
        url "https://www.googleapis.com/oauth2/v3/userinfo"
        sub (:sub (jwt/unsign id-token @google-public-key {:alg :rs256
                                                           :iss "accounts.google.com"}))
        account-id [:account.provider.google/sub sub]
        provider-info (-> (http/get url {:headers {"Authorization" (str "Bearer " token)}})
                          :body
                          (json/parse-string keyword))]
    (db/transact! (account-tx account-id provider-info))
    (-> (ring.response/redirect (routes/path-for [:home]))
        (assoc-in [:cookies "account-id"] {:value (t/write account-id)
                                           :http-only true
                                           :path "/"
                                           :secure (= (env/config :env) "prod")}))))