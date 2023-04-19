(ns sparkboard.server.impl
  (:require [clojure.string :as str]
            [sparkboard.firebase.tokens :as fire-tokens]
            [taoensso.timbre :as log]))

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

;; function to check if x satisfies clojure.lang.IRef
(defn watchable? [x]
  (and (some? x) (instance? clojure.lang.IRef x)))


(defn wrap-reaction [f]
  (fn [req]
    (let [res (f req)]
      (if (watchable? res)
        (try (let [body @res]
               {:status 200 :body body})
             (catch Exception e
               {:status 500 :body (str "Error: " (.getMessage e))}))
        res))))

(defn join-handlers
  "Join a sequence of handlers into a single handler, returning the first non-nil response."
  [& handlers]
  (fn [req]
    (some (fn [handler] (handler req)) handlers)))