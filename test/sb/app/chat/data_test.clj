(ns sb.app.chat.data-test
  (:require [clojure.test :refer [deftest testing is]]
            [sb.app.chat.data :as cd]
            [re-db.api :as db]
            [sb.authorize :as az]
            [sb.schema :as sch]
            [sb.server.datalevin :as dl]
            ;; TODO need to require this for the schema to be properly defined, but is slow
            [sb.app]))

(defn get-test-db-path []
  ;; Datalevin doesn't have an in-memory mode therefore we create a test database in tmpdir
  ;; On systems where /tmp is mounted as a in-memory virtual filesystem this is fine
  ;; for other systems it might make sense to explore using datascript as drop-in
  ;; replacement for datalevin for testing purposes.
  (str (System/getProperty "java.io.tmpdir")
       "/sparkboard-test-" (System/currentTimeMillis) "-" (rand-int 10000000)))

(deftest test-new-message!
  (let [conn (datalevin.core/get-conn (get-test-db-path) {} dl/db-opts)]
    (db/with-conn conn
      (with-redefs [dl/conn conn]
        ;; transact schema
        (db/merge-schema! @sch/!schema)

        (let [tx-report (db/transact! [(dl/new-entity {:db/id "new-board"} :board)
                                       (dl/new-entity {:db/id "alice-account"} :account)
                                       (dl/new-entity {:db/id "bob-account" :account/email "bob"} :account)
                                       (dl/new-entity {:db/id "eve-account" :account/email "eve"} :account)
                                       (dl/new-entity {:db/id "alice" :membership/entity "new-board"
                                                       :membership/member "alice-account"}
                                                      :membership)
                                       (dl/new-entity {:db/id "bob" :membership/entity "new-board"
                                                       :membership/member "bob-account"}
                                                      :membership)
                                       (dl/new-entity {:db/id "eve" :membership/entity "new-board"
                                                       :membership/member "eve-account"}
                                                      :membership)])
              {:strs [new-board alice alice-account bob bob-account eve eve-account]} (-> tx-report :tempids)
              chat-id1 (:chat-id (cd/new-message! {:account-id (sch/wrap-id (db/entity alice-account))
                                                   :other-id (sch/wrap-id (db/entity bob))}
                                                  "hello1"))
              chat-id2 (:chat-id (cd/new-message! {:account-id (sch/wrap-id (db/entity alice-account))
                                                   :other-id (sch/wrap-id (db/entity bob))}
                                                  "hello2"))
              alice-id (:entity/id (db/entity alice))
              bob-id (:entity/id (db/entity bob))]
          (testing "two messages from alice to bob end up in the same chat"
            (is (= chat-id1 chat-id2)))
          (testing "alice can also add messages per chat-id"
            (cd/new-message! {:account-id (sch/wrap-id (db/entity alice-account))
                              :chat-id chat-id1}
                             "hello3"))
          (testing "bob can reply"
            (cd/new-message! {:account-id (sch/wrap-id (db/entity bob-account))
                              :chat-id chat-id1}
                             "hello4"))
          (testing "eve can't can reply"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"You are not a participant in this chat"
                         (cd/new-message! {:account-id (sch/wrap-id (db/entity eve-account))
                                           :chat-id chat-id1}
                                          "hello5"))))
          (testing "alice and bob can read the chat"
            (is (= [[alice-id "hello1"]
                    [alice-id "hello2"]
                    [alice-id "hello3"]
                    [bob-id "hello4"]]
                   (->> (cd/chat (az/with-account-id! {:account (db/entity alice-account)} {:chat-id chat-id1}))
                        :chat/messages
                        (map (juxt (comp :entity/id :entity/created-by) :chat.message/content)))
                   (->> (cd/chat (az/with-account-id! {:account (db/entity bob-account)} {:chat-id chat-id1}))
                        :chat/messages
                        (map (juxt (comp :entity/id :entity/created-by) :chat.message/content))))))
          (testing "eve cannot read the the chat"
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"You are not a participant in this chat"
                 (cd/chat (az/with-account-id! {:account (db/entity eve-account)} {:chat-id chat-id1}))))))))))
