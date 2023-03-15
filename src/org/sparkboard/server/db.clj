(ns org.sparkboard.server.db
  "Database queries and mutations (transactions)"
  (:require [buddy.hashers]
            [clojure.pprint :refer [pprint]]
            [clojure.set :refer [rename-keys]]
            [clojure.string :as str]
            [datalevin.core :as dl]
            [malli.core :as m]
            [org.sparkboard.datalevin :as sb.datalevin :refer [conn]]
            [org.sparkboard.schema :as schema]
            [re-db.api :as db]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.read :as read]
            [re-db.xform :as xf]
            [ring.util.http-response :as http-rsp]
            [tools.sparkboard.util :as util]))

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
  (dissoc (db/pull '[*
                     {:member/tags [*]}]
                   [:member/id id])
          :member/password))

;;; private messaging / "chat"
(defquery $message-thread:one [{:message.thread/keys [id]}]
  (->> (db/where [[:message.thread/id id]])
       (map (re-db.api/pull '[:message.thread/id
                              :message.thread/topic
                              :ts/created-by]))
       first)
  ;; FIXME switch back to `pull`, after we TODO add uniqueness constraint to the schema on `:message.thread/id`
  #_ (db/pull '[:message.thread/id
             :message.thread/topic
             :message.thread/members]
           [:message.thread/id id]))

(defquery $message:index [{:message.thread/keys [id]}]
  (->> (db/where [[:message/thread id]])
       (map (re-db.api/pull '[:message/id
                              :message/sender
                              :message/contents
                              :message/timestamp]))))

;;;
(defquery $search [{:keys [query-params org/id]}]
  (->> (sb.datalevin/q-fulltext-in-org (:q query-params)
                                       id)
       ;; Can't send Entities over the wire, so:
       (map (db/pull '[:project/title
                       :board/title]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Mutations

(def buddy-opts
  "Options for Buddy-hashers"
  {;; TODO allow config of `:iterations`, perhaps per
   ;; https://security.stackexchange.com/questions/3959/recommended-of-iterations-when-using-pbkdf2-sha256/3993#3993
   :alg :bcrypt+blake2b-512})

(defn password-ok?
  "Internal password check fn.
  Returns map describing whether given password attempt matches what
  we have stored for the member of given name:
    - `valid?` key is a Boolean describing success/failure
    - failures/mismatches contain an `error` key (kw or map value)
  Exists to paper over buddy-hashers' API."
  [member-name pwd-attempt]
  (if-let [pwd-hash (dl/q '[:find ?pwd .
                            :in $ ?nom
                            :where
                            [?mbr :member/name ?nom]
                            [?mbr :member/password ?pwd]]
                          @conn member-name)]
    (try (let [res (buddy.hashers/verify pwd-attempt pwd-hash)]
           (cond-> res
             ;; unify map representation to `valid?` versus `error`
             (not (:valid res))
             (assoc :error :error/invalid-password)
             true
             (update-keys #(get {:valid :valid?} % %))))
         (catch java.lang.IllegalArgumentException iae
           (cond-> {:error :error/uncategorized
                    :member/name member-name}
             ;; it would be cool if the exception returned data but it doesn't
             (= "invalid arguments" (ex-message iae))
             (assoc :error :error/no-password-provided)))
         (catch clojure.lang.ExceptionInfo cle
           (cond-> {:error {}
                    :member/name member-name}
             ;; it would be cool if the exception returned data but it doesn't
             (= "Malformed hash" (ex-message cle))
             (assoc :error {:error/kind :error/malformed-password-hash
                            :error/message (ex-message cle)}))))
    {:error :error/member-password-not-found
     :member/name member-name}))

(defn logout-handler
  "HTTP handler. Returns 200/OK after logging the user out."
  [_ctx _params]
  (dissoc {:status 200, :session nil,
           :cookies {"ring-session" {:value ""
                                     :expires (-> (java.time.LocalDateTime/now)
                                                  (.minusDays 1)
                                                  .toString)
                                     :max-age 1}}}
          :identity))

(defn login-handler
  "HTTP handler. Returns 200/OK with result of the user/password attempt in the body.
  Body keys:
   - `member/name` - who tried to log in
   - `valid?` - success/failure Boolean
   - `error` - describes what went wrong (if anything)"
  [_ctx _params form]
  (cond-> {:status 200 ;; Leave HTTP error codes for the transport layer
           :body {:success? false}}
    ((every-pred :valid?
                 (complement :error))
     (password-ok? (:member/name form)
                   (:member/password form)))
    (assoc :body {:success? true, :session {:identity (:member/name form)}}
           :session {:identity (:member/name form)})))

(comment ;;;; Password encryption
  ;; NB: resulting string is concatenation of algorithm, plaintext salt, and encrypted password
  (buddy.hashers/derive "secretpassword" buddy-opts)
  
  (buddy.hashers/verify "secretpassword" "bcrypt+blake2b-512$dfb9ecb7a246d546e437418e6cd53b43$12$51fb8cef3a64337e0677182171786b14c14a40dc60f5f95c")

  (db/where [[:member/name "dave888"]])

  (:member/password (first (db/where [[:member/name " Desiree Nsanzabera"]])))

  (dl/q '[:find ?pwd .
          :in $ ?nom
          :where
          [?mbr :member/name ?nom]
          [?mbr :member/password ?pwd]]
        @conn "dave888")

  (password-ok? nil nil)
  (password-ok? "foo" nil)
  (password-ok? nil "nil")
  (password-ok? "dave888" nil)
  (password-ok? "dave888" "")
  (password-ok? " Desiree Nsanzabera" nil)
  (password-ok? "dave888" "secretpassword")
  (password-ok? "dave888" "wrong")

  ;; Every unhappy password is unhappy in its own way (but returns the same HTTP response, for security)
  (login-handler nil nil nil)
  (login-handler nil nil {})
  (login-handler nil nil {:member/name "dave888"})
  (login-handler nil nil {:member/password "this makes no sense"})
  (login-handler nil nil {:member/name "", :member/password "shouldn't-matter"})
  (login-handler nil nil {:member/name "dave888", :member/password ""})
  (login-handler nil nil {:member/name "dave888", :member/password "wrong"})
  (login-handler nil nil {:member/name "doesnt-exist", :member/password "doesn't-matter"})
  ;; Happy passwords are all alike  
  (login-handler nil nil {:member/name "dave888", :member/password "secretpassword"})

  )

(defn make-member [_ctx m]
  (-> m
      (assoc :member/id (str (random-uuid))
             ;; FIXME use context to hook this to actual current user
             :ts/created-by {:firebase-account/id "DEV:FAKE"})
      (update :member/password #(buddy.hashers/derive % buddy-opts))
      (util/guard (partial m/validate (:member schema/proto)))))

(defn member:create [ctx params mbr]
  (try (if (empty? (db/where [[:member/name (:member/name mbr)]]))
         (if-let [mbr (make-member ctx (assoc mbr :member/board
                                              [:board/id (:board/id params)]))]
           (do (tap> (db/transact! [mbr]))
               (http-rsp/ok mbr))
           (http-rsp/bad-request {:error "can't create member with given data"
                                  :data mbr}))
         (http-rsp/bad-request {:error "member with that title already exists"
                                :data mbr}))
       (catch Exception e
         (http-rsp/internal-server-error {:error (.getMessage e)}))))


(defn make-project [_ctx m]
  (util/guard (assoc m
                     :project/id (str (random-uuid))
                     ;; FIXME use context to hook this to actual current user
                     :ts/created-by {:firebase-account/id "DEV:FAKE"})
              (partial m/validate (:project schema/proto))))

(defn project:create [ctx params project]
  (try (if (empty? (db/where [[:project/title (:project/title project)]]))
         (if-let [project (make-project ctx (assoc project :project/board
                                                   [:board/id (:board/id params)]))]
           (do (tap> (db/transact! [project]))
               (http-rsp/ok project))
           (http-rsp/bad-request {:error "can't create project with given data"
                                  :data project}))
         (http-rsp/bad-request {:error "project with that title already exists"
                                :data project}))
       (catch Exception e
         (http-rsp/internal-server-error {:error (.getMessage e)}))))

(defn make-board [_ctx m]
  (util/guard (assoc m
                     :board/id (str (random-uuid))
                     ;; FIXME use context to hook this to actual current user
                     :ts/created-by {:firebase-account/id "DEV:FAKE"})
              (partial m/validate (:board schema/proto))))

(defn board:create [ctx params board]
  (try (if (empty? (db/where [[:board/title (:board/title board)]]))
         (if-let [board (make-board ctx (assoc board :board/org [:org/id (:org/id params)]))]
           (do (tap> (db/transact! [board]))
               (http-rsp/ok board))
           (http-rsp/bad-request {:error "can't create board with given data"
                                  :data board}))
         (http-rsp/bad-request {:error "board with that title already exists"
                                :data board}))
       (catch Exception e
         (http-rsp/internal-server-error {:error (.getMessage e)}))))

(defn title->id [s]
  (-> s
      str/lower-case
      (str/replace #"\s" "")))

(defn make-org [_ctx m]
  (util/guard (assoc m
                     ;; TODO maybe allow user to specify id?
                     :org/id (title->id (:org/title m))
                     ;; FIXME use context to hook this to actual current user
                     :ts/created-by {:firebase-account/id "DEV:FAKE"})
              (partial m/validate (:org schema/proto))))

(defn org:create [ctx _params org]
  ;; open questions:
  ;; - return value of a mutation goes where? (eg. errors, messages...)
  ;; TODO
  ;; - better way to generate UUIDs?
  ;; - schema validation
  ;; - error/validation messages (handle in client)
  (try (if (empty? (db/where [[:org/title (:org/title org)]]))
         (if-let [org (make-org ctx org)]
           (do (tap> (db/transact! [org]))
               (http-rsp/ok org))
           (http-rsp/bad-request {:error "can't create org with given data"
                                  :data org}))
         (http-rsp/bad-request {:error "org with that title already exists"
                                :data org}))
       (catch Exception e
         (http-rsp/internal-server-error {:error (.getMessage e)}))))

(comment
  (make-org {} {:org/title "foo bar baz qux"})

  (make-org {} {:org/title "foo", :foo "bar"})
  
  (org:create {} nil
              {:org/title "foo"})

  ;; fail b/c no title and extra key
  (org:create {} nil
              {:foo "foo"})

  ;; fail b/c exists
  (org:create {} nil
              {:org/title "Hacking Health"})

  (org:create {} nil
              {:foo "foo" ;; <-- should stop the gears
               :org/title "baz"})

  )

(defn org:delete
  "Mutation fn. Retracts organization by given org-id."
  [_ctx _params org-id]
  ;; FIXME access control
  (if-let [eid (dl/q '[:find ?e .
                       :in $ ?org-id
                       :where [?e :org/id ?org-id]]
                     @conn org-id)]
    (do (db/transact! [[:db.fn/retractEntity eid]])
        (http-rsp/ok {:org/id org-id, :deleted? true}))
    (http-rsp/bad-request {:org/id org-id, :deleted? false})))


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

;;; messaging

(defn make-message-thread [_ctx m]
  (-> m
      (assoc :message.thread/id (str (random-uuid))
             ;; FIXME use context to hook this to actual current user
             :ts/created-by {:firebase-account/id "DEV:FAKE"})
      (util/guard (partial m/validate (:message-thread schema/proto)))))

(defn make-message [_ctx m]
  (-> m
      (assoc :message/id (str (random-uuid))
             ;; FIXME use context to hook this to actual current user
             :ts/created-by {:firebase-account/id "DEV:FAKE"})
      (util/guard (partial m/validate (:message schema/proto)))))

;; TODO message:create

;; TODO message-thread:create

(comment ;;; developing private messaging poc
  (make-message-thread {} {:message.thread/topic "our nice little private messaging topic"})
  
  (db/transact! [{:message.thread/topic "our nice little private messaging topic",
                  :message.thread/id "0beff516-ec33-415f-aacd-92f1328e4698",
                  :ts/created-by #:firebase-account{:id "DEV:FAKE"}}])
  
  (deref ($message-thread:one {:message.thread/id "0beff516-ec33-415f-aacd-92f1328e4698"}))

  ;; FIXME
  (db/pull '[:message.thread/id
             :message.thread/topic
             :message.thread/members]
           [:message.thread/id "foo"])

  ;;; msgs in that topic
  (deref ($message:index {:message.thread/id "0beff516-ec33-415f-aacd-92f1328e4698"}))
  
  (make-message {} {:message/thread [:message.thread/id "0beff516-ec33-415f-aacd-92f1328e4698"]
                    :message/sender [:member/name "dave888"]
                    :message/contents "hello alice"})
  
  (db/transact! [{:message/thread [:message.thread/id "0beff516-ec33-415f-aacd-92f1328e4698"],
                  :message/sender [:member/name "dave888"],
                  :message/contents "hello alice",
                  :message/id "69ffcef5-7401-4a55-9c93-f5110a2710e1",
                  :ts/created-by #:firebase-account{:id "DEV:FAKE"}}
                 {:message/thread [:message.thread/id "0beff516-ec33-415f-aacd-92f1328e4698"],
                  :message/sender [:member/name "dave888"],
                  :message/contents "hello bob",
                  :message/id "0d1dcaf2-9be1-441f-924d-9c02c5424f3e"
                  :ts/created-by #:firebase-account{:id "DEV:FAKE"}}])

  (db/where [[:message/id "0d1dcaf2-9be1-441f-924d-9c02c5424f3e"]])

  (db/where [[:message.thread/id "0beff516-ec33-415f-aacd-92f1328e4698"]])

  (map deref (take 3 (db/where [:project/id])))
  ;; FIXME refs are broken, maybe need to define it in schema 
  )
