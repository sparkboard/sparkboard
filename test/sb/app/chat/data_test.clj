(ns sb.app.chat.data-test
  (:require [clojure.test :refer [deftest testing is]]
            [sb.app.chat.data :as cd]
            [sb.app.content.data] ;; needed for schema definition
            [sb.app.notification.data]  ;; needed for schema definition
            [re-db.api :as db]
            [sb.authorize :as az]
            [sb.schema :as sch]
            [sb.server.datalevin :as dl]))

(sch/install-malli-schemas!)

(defn get-test-db-path []
  ;; Datalevin doesn't have an in-memory mode therefore we create a test database in tmpdir
  ;; On systems where /tmp is mounted as a in-memory virtual filesystem this is fine
  ;; for other systems it might make sense to explore using datascript as drop-in
  ;; replacement for datalevin for testing purposes.
  (str (System/getProperty "java.io.tmpdir")
       "/sparkboard-test-" (System/currentTimeMillis) "-" (rand-int 10000000)))

(defn prose [content]
  {:prose/format :prose.format/markdown
   :prose/string content})

(deftest test-new-message!
  (let [conn (datalevin.core/get-conn (get-test-db-path) {} dl/db-opts)]
    (db/with-conn conn
      (with-redefs [dl/conn conn]
        ;; transact schema
        (db/merge-schema! @sch/!schema)

        (let [tx-report (db/transact! [(dl/new-entity {:db/id "alice"} :account)
                                       (dl/new-entity {:db/id "bob" :account/email "bob"} :account)
                                       (dl/new-entity {:db/id "eve" :account/email "eve"} :account)])
              {:strs [alice bob eve]} (-> tx-report :tempids)
              chat-id1 (:chat-id (cd/new-message! {:account-id (sch/wrap-id (db/entity alice))
                                                   :other-id (sch/wrap-id (db/entity bob))}
                                                  (prose "hello1")))
              chat-id2 (:chat-id (cd/new-message! {:account-id (sch/wrap-id (db/entity alice))
                                                   :other-id (sch/wrap-id (db/entity bob))}
                                                  (prose "hello2")))
              alice-id (:entity/id (db/entity alice))
              bob-id (:entity/id (db/entity bob))]
          (testing "two messages from alice to bob end up in the same chat"
            (is (= chat-id1 chat-id2)))
          (testing "bob can reply"
            (cd/new-message! {:account-id (sch/wrap-id (db/entity bob))
                              :other-id (sch/wrap-id (db/entity alice))}
                             (prose "hello3")))
          (testing "alice and bob can read the chat"
            (is (= [[alice-id (prose "hello1")]
                    [alice-id (prose "hello2")]
                    [bob-id (prose "hello3")]]
                   (->> (cd/chat (az/with-account-id! {:account (db/entity alice)}
                                   {:other-id (sch/wrap-id (db/entity bob))}))
                        (map (juxt (comp :entity/id :entity/created-by) :chat.message/content)))
                   (->> (cd/chat (az/with-account-id! {:account (db/entity bob)}
                                   {:other-id (sch/wrap-id (db/entity alice))}))
                        (map (juxt (comp :entity/id :entity/created-by) :chat.message/content))))))
          (testing "eve cannot read the the chat"
            (is (= []
                   (->> (cd/chat (az/with-account-id! {:account (db/entity eve)}
                                   {:other-id (sch/wrap-id (db/entity bob))}))
                        :chat/messages
                        (map (juxt (comp :entity/id :entity/created-by) :chat.message/content)))
                   (->> (cd/chat (az/with-account-id! {:account (db/entity eve)}
                                   {:other-id (sch/wrap-id (db/entity alice))}))
                        :chat/messages
                        (map (juxt (comp :entity/id :entity/created-by) :chat.message/content)))))))))))
