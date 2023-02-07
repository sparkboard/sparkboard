(ns org.sparkboard.server.queries
  (:require [org.sparkboard.datalevin :as sb.datalevin :refer [conn]]
            [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.xform :as xf]
            [re-db.read :as read]
            [clojure.pprint :refer [pprint]]))

(defmacro defquery
  "Defines a query function. The function will be memoized and return a {:value / :error} map."
  [name & fn-body]
  (let [[doc [argv & body]] (if (string? (first fn-body))
                              [(first fn-body) (rest fn-body)]
                              [nil fn-body])]
    `(memo/defn-memo ~name ~argv
       (r/reaction ~@body))))

(defquery $org:index [_]
  (->> (db/where [:org/id])
       (mapv (re-db.api/pull '[*]))))

(comment
 (db/transact! [{:org/id (str (rand-int 10000))
                 :org/title (str (rand-int 10000))}]))

(defquery $org:one [{:keys [org/id]}]
  (db/pull '[:org/id
             :org/title
             {:board/_org [:ts/created-at
                           :board/id
                           :board/title]}
             {:entity/domain [:domain/name]}]
           [:org/id id]))

(defquery $board:one [{:keys [board/id]}]
  (db/pull '[*
             :board/title
             :board.registration/open?
             :board/title
             {:project/_board [*]}
             {:board/org [:org/title :org/id]}
             {:member/_board [*]}
             {:entity/domain [:domain/name]}]
           [:board/id id]))

(defquery $project:one [{:keys [project/id]}]
  (db/pull '[*] [:project/id id]))

(defquery $member:one [{:keys [member/id]}]
  (db/pull '[*
             {:member/tags [*]}]
           [:member/id id]))

(defquery $search [{:keys [query-params org/id]}]
  (->> (sb.datalevin/q-fulltext-in-org (:q query-params)
                                       id)
       ;; Can't send Entities over the wire, so:
       (map (db/pull '[:project/title
                       :board/title]))))


(defn board:create [context {:as params :keys [org/id]} board]
  ;; open questions:
  ;; - return value of a mutation goes where? (eg. errors, messages...)
  (prn :params params :board/create board))

(defn return [body]
  {:status 200
   ;; FIXME unsure if this works / is necessary / what we should do w/muuntaja
   :headers {"content-type" "application/transit+json"}
   :body body})

(defn org:create [context _params org]
  ;; open questions:
  ;; - return value of a mutation goes where? (eg. errors, messages...)

  ;; TODO
  ;; - better way to generate UUIDs?
  ;; - schema validation
  ;; - error/validation messages (handle in client)
  (try (let [org (assoc org :org/id (str (random-uuid))
                            :ts/created-by {:firebase-account/id "DEV:FAKE"})]
         (tap> (db/transact! [org]))
         {:status 200
          :body (select-keys org [:org/id])})
       (catch Exception e
         {:status 500
          :body {:error (.getMessage e)}})))

(comment
 (r/redef !k (r/reaction 100))
 (def !kmap (xf/map inc !k))
 (add-watch !kmap :prn (fn [_ _ _ v] (prn :!kmap v)))
 (swap! !k inc)
 (r/become !k (r/reaction 10))


 (r/session
  (let [!orgs (r/reaction
                (prn :counting-orgs)
                (count (db/where [:org/title])))]
    (prn @!orgs)
    (pprint (db/transact! [{:org/id (str (rand-int 10000))
                            :org/title (str (rand-int 10000))}]))
    (prn @!orgs)

    ))

 )