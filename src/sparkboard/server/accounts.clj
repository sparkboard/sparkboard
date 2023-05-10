(ns sparkboard.server.accounts
  (:require [buddy.core.keys :as buddy.keys]
            [buddy.sign.jwt :as jwt]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [sparkboard.server.validate :as vd]
            [sparkboard.transit :as t]
            [sparkboard.routes :as routes]
            [sparkboard.server.env :as env]
            [re-db.api :as db]
            [ring.middleware.oauth2 :as oauth2]
            [ring.middleware.session :as ring.session]
            [ring.middleware.session.cookie :as ring.session.cookie]
            [ring.util.response :as ring.response])
  (:import [com.smartmovesystems.hashcheck FirebaseScrypt]
           [org.apache.commons.codec.binary Base64]
           [java.nio.charset StandardCharsets]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Password authentication using the Firebase SCRYPT hashing algorithm

(def hash-config (:password-auth/hash-config (cond-> env/config
                                                     (not (= "staging" (:env env/config)))
                                                     :prod)))

(defn generate-salt
  "Returns a base64-encoded random salt."
  [length]
  (-> (byte-array length)
      (doto (->> (.nextBytes (java.security.SecureRandom.))))
      Base64/encodeBase64
      (String. StandardCharsets/US_ASCII)))


(defn hash-password [password]
  (let [signer-bytes
        (-> (:base64-signer-key hash-config)
            (.getBytes StandardCharsets/US_ASCII)
            Base64/decodeBase64)
        base64-salt (generate-salt 16)]
    {:account/password-salt base64-salt
     :account/password-hash (-> (FirebaseScrypt/hashWithSalt
                                 password
                                 base64-salt
                                 (:base64-salt-separator hash-config)
                                 (:rounds hash-config)
                                 (:mem-cost hash-config))
                                (->> (FirebaseScrypt/encrypt signer-bytes))
                                Base64/encodeBase64
                                (String. StandardCharsets/US_ASCII))}))

(defn password-valid? [{:account/keys [password-hash password-salt]} password]
  (try (FirebaseScrypt/check
        password
        password-hash
        password-salt
        (:base64-salt-separator hash-config)
        (:base64-signer-key hash-config)
        (:rounds hash-config)
        (:mem-cost hash-config))
       (catch Exception e (throw (ex-info "Error checking password" {:status 401} e)))))

(comment
 (password-valid? (hash-password "abba") "abba"))

;; todo
;; - registration screen (new accounts)
;; - password reset flow
;; - trigger the password reset flow (with message) if someone tries to sign in
;;   and we don't have a password or google account,

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Account lookup

(def exposed-account-keys [:db/id
                           :account/display-name
                           :account/email
                           :account/photo-url
                           :account/locale])

(defn wrap-account-lookup [handler]
  (fn [req]
    (handler (assoc req :account
                        (when-let [account-id (try (some-> (get-in req [:cookies "account-id" :value])
                                                           t/read)
                                                   (catch Exception e nil))]
                          (try (not-empty (db/pull exposed-account-keys account-id))
                               (catch Exception e
                                 (throw
                                  (ex-info "Account not found"
                                           {:status 401
                                            :account-id account-id})) nil)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Session handlers

(defn res:logout [res]
  (assoc-in res [:cookies "account-id"]
            {:value (t/write nil)
             :expires (str (-> (java.time.LocalDateTime/now)
                               (.minusDays 1)
                               .toString))}))

(defn logout-handler [_ _]
  (-> (ring.response/redirect (routes/path-for :home))
      (res:logout)))

(defn res:login [res account-id]
  (-> res
      (assoc-in [:cookies "account-id"]
                {:value (t/write account-id)
                 ;; expire in 2 weeks
                 :expires (str (java.util.Date. (+ (System/currentTimeMillis) (* 1000 60 60 24 14))))
                 :http-only true
                 :path "/"
                 :secure (= (env/config :env) "prod")})
      ;; TODO
      ;; - verify that this value is encoded properly somewhere,
      ;; - in client, set this into :env/account
      (assoc :body {:account (db/pull exposed-account-keys account-id)})))

(defn login-handler
  "POST handler. Returns 200/OK with account data if successful."
  [_req _params {:as account
                 :keys [account/email
                        account/password]}]

  (vd/assert account [:map
                      [:account/password [:string {:min 8}]]
                      [:account/email [:re #"^[^@]+@[^@]+$"]]])

  (let [account-entity (not-empty (db/get [:account/email email]))
        _ (vd/assert account-entity
                     [:map {:error/message "Account not found"}
                      [:account/password-hash [:string]]
                      [:account/password-salt [:string]]]
                     {:code 401})
        _ (when-not account-entity (throw (ex-info (str "Account not found") {:account/email email :status 401})))
        _ (when (or (not (:account/password-hash account-entity))
                    (not (:account/password-salt account-entity)))
            ;; TODO start password-reset flow
            (throw (ex-info "This account requires a password reset."
                            {:account/email email
                             :status 401})))]
    (if (password-valid? account-entity password)
      (res:login {:status 200} [:account/email email])
      (throw (ex-info "Invalid password" {:account/email email
                                          :status 401})))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Oauth2 authentication

(def oauth2-config {:google
                    {:client-id (-> env/config :oauth.google/client-id)
                     :client-secret (-> env/config :oauth.google/client-secret)
                     :scopes ["profile openid email"]
                     :authorize-uri "https://accounts.google.com/o/oauth2/v2/auth"
                     :access-token-uri "https://accounts.google.com/o/oauth2/token"
                     :launch-uri (routes/path-for [:oauth2.google/launch])
                     :redirect-uri (routes/path-for [:oauth2.google/callback])
                     :landing-uri (routes/path-for [:oauth2.google/landing])}})

(defn keep-vals [m]
  (into {} (filter (comp some? val) m)))

(defn google-account-tx [account-id provider-info]
  (let [existing (not-empty (db/pull '[*] account-id))
        now (java.util.Date.)]
    [(merge
      ;; backfill account info (do not overwrite current data)
      (keep-vals
       {:ts/created-at now
        :account.provider.google/sub (:sub provider-info)
        :account/display-name (:name provider-info)
        :account/email (:email provider-info)
        :account/email-verified? (:email_verified provider-info)
        :account/photo-url (:picture provider-info)
        :account/locale (some-> (:locale provider-info) (str/split #"-") first)})
      existing
      ;; last-sign-in can overwrite existing data
      {:account/last-sign-in now})]))

(defn fetch-google-public-keys
  "Returns map of {kid, java.security.PublicKey} for google's current oauth2 public certs"
  []
  (-> "https://www.googleapis.com/oauth2/v3/certs"
      http/get
      :body
      (json/parse-string keyword)
      :keys
      (->>
        (map (juxt :kid buddy.keys/jwk->public-key))
        (into {}))))

(let [!cache (atom nil)]
  (defn google-public-key
    "Returns google public key for given kid"
    [kid]
    (or (get @!cache kid)
        (do (swap! !cache merge (fetch-google-public-keys))
            (get @!cache kid)))))

(defn id-token-header
  "Returns map of header values from id-token."
  [id-token]
  (-> id-token
      (str/split #"\.")
      first
      (.getBytes StandardCharsets/US_ASCII)
      Base64/decodeBase64
      (String. StandardCharsets/US_ASCII)
      (json/parse-string keyword)))

(defn google-landing [{:keys [oauth2/access-tokens]} _]
  (let [{:keys [token id-token]} (:google access-tokens)
        _ (def google-access-tokens (:google access-tokens))
        public-key (-> id-token id-token-header :kid google-public-key)
        url "https://www.googleapis.com/oauth2/v3/userinfo"
        sub (:sub (jwt/unsign id-token public-key {:alg :rs256
                                                           :iss "accounts.google.com"}))
        account-id [:account.provider.google/sub sub]
        provider-info (-> (http/get url {:headers {"Authorization" (str "Bearer " token)}})
                          :body
                          (json/parse-string keyword))]
    (db/transact! (google-account-tx account-id provider-info))
    (-> (ring.response/redirect (routes/path-for [:home]))
        (res:login account-id))))

(comment
  (-> (:id-token google-access-tokens)
      (str/split #"\.")
      first
      (.getBytes StandardCharsets/US_ASCII)
      Base64/decodeBase64
      (String. StandardCharsets/US_ASCII)
      (json/parse-string keyword))
  (keys @!google-public-keys)
  google-access-tokens
  (-> "https://www.googleapis.com/oauth2/v3/certs"
      http/get
      :body
      (json/parse-string keyword)
      :keys
      (->> (group-by :kid))
      (update-vals first))
  (jwt/unsign (:id-token google-access-tokens)
              (-> "https://www.googleapis.com/oauth2/v3/certs"
                  http/get
                  :body
                  (json/parse-string keyword)
                  :keys
                  first
                  buddy.keys/jwk->public-key)
              {:alg :rs256
               :iss "accounts.google.com"
               :skip-validation true}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Middleware

(defn wrap-accounts [f]
  (-> f
      (wrap-account-lookup)
      (oauth2/wrap-oauth2 oauth2-config)
      (ring.session/wrap-session {:store (ring.session.cookie/cookie-store
                                          {:key (byte-array (get env/config :webserver.cookie/key (repeatedly 16 (partial rand-int 10))))
                                           :readers {'clj-time/date-time clj-time.coerce/from-string}})
                                  :cookie-attrs {:http-only true :secure (= (env/config :env) "prod")}})))


