(ns sparkboard.authorize)

(defn require-account [req params]
  (when-not (-> req :account :entity/id)
    (throw (ex-info "User not signed in" {:code 400})))
  params)

(defn with-account-id [req params]
  (assoc params :account-id [:entity/id (-> req :account :entity/id)]))

(defn unauthorized! [message & [data]]
  (throw (ex-info message (merge {:code 400} data))))

(defn with-account-id! [req params]
  (let [params (with-account-id req params)]
    (when-not (:account-id params)
      (unauthorized! "User not signed in"))
    params))