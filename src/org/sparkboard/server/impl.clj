(ns org.sparkboard.server.impl)

(defn req-auth-token [req]
  (or (some-> (:headers req) (get "authorization") (str/replace #"^Bearer: " ""))
      (-> req :params :token)))

(defn return-text [status message]
  {:status status
   :headers {"Content-Type" "text/plain"}
   :body message})

(defn wrap-sparkboard-verify
  "must be a Sparkboard server request"
  [f & claims-checks]
  (fn [req]
    (let [token (req-auth-token req)
          claims (some-> token (fire-tokens/decode))
          check-claims (or (some->> claims-checks seq (apply every-pred)) (constantly true))]
      (if (check-claims claims)
        (f (assoc req :auth/token-claims claims))
        (do
          (log/warn "Sparkboard token verification failed." {:uri (:uri req)
                                                             :claims claims
                                                             :token token})
          (return-text 401 "Invalid token"))))))


