(ns org.sparkboard.firebase.jvm
  (:refer-clojure :exclude [read])
  (:require [clojure.java.io :as io]
            [clojure.walk :as walk]
            [org.sparkboard.server.env :as env]
            [tools.sparkboard.js-convert :refer [clj->json]]
            [taoensso.timbre :as log])
  (:import (com.google.auth.oauth2 ServiceAccountCredentials)
           (com.google.firebase FirebaseApp FirebaseOptions$Builder)
           (com.google.firebase.auth FirebaseAuth)
           (com.google.firebase.database DatabaseReference$CompletionListener FirebaseDatabase ValueEventListener)))

(defonce app
         (delay
           (let [service-account (:firebase/service-account env/config)
                 database-url (-> env/config :firebase/app-config :databaseURL)]
             (-> (new FirebaseOptions$Builder)
                 (.setCredentials
                   (ServiceAccountCredentials/fromStream
                     (-> service-account
                         clj->json
                         .getBytes
                         io/input-stream)))
                 (.setDatabaseUrl database-url)
                 (.build)
                 (FirebaseApp/initializeApp)))))

(def db (delay (FirebaseDatabase/getInstance @app)))
(def auth (delay (FirebaseAuth/getInstance @app)))

(defprotocol IConvertToClojure
  (->clj [o]))

(extend-protocol IConvertToClojure
  java.util.Map
  (->clj [o] (let [entries (.entrySet o)]
               (reduce (fn [m [^String k v]]
                         (assoc m (keyword k) (->clj v)))
                       {} entries)))

  java.util.List
  (->clj [o] (vec (map ->clj o)))

  java.lang.Object
  (->clj [o] o)

  nil
  (->clj [_] nil))

(defn reify-value-listener [on-value on-error]
  (reify ValueEventListener
    (onDataChange [this dataSnapshot] (on-value dataSnapshot))
    (onCancelled [this databaseError] (on-error databaseError))))

(defn reify-completion-listener [on-complete]
  (reify DatabaseReference$CompletionListener
    (onComplete [this databaseError databaseReference]
      (on-complete databaseError databaseReference))))

(defonce ->ref (memoize (fn [path] (cond->> path (string? path) (.getReference @db)))))

(defn listen [path on-value on-error]
  (let [listener (reify-value-listener on-value on-error)]
    (.addValueEventListener (->ref path) listener)
    #(.removeEventListener (->ref path) listener)))

(defn listen-once [path on-value on-error]
  (.addListenerForSingleValueEvent
    (->ref path)
    (reify-value-listener on-value on-error)))

(defn ref-path [ref]
  (-> ref (.getPath) str))

(defn read [path]
  (let [p (promise)]
    (listen-once path
                 #(deliver p (let [val (->clj (.getValue %))]
                               (log/trace :read-val (str path) val)
                               val))
                 #(throw %))
    @p))

;; synchronize the entire db
(defonce ^:private *stop-sync-all (atom nil))

(defn stop-sync-all []
  (when-let [unlisten @*stop-sync-all]
    (unlisten)
    (reset! *stop-sync-all nil)))

(defn sync-all []
  (let [p (promise)]
    (swap! *stop-sync-all
           (fn [unlisten]
             (when unlisten (unlisten))
             (listen "/" (fn [snap] (if (realized? p) snap (deliver p snap))) identity)))
    p))

(defn set-value [path value]
  (let [p (promise)]
    (.setValue (->ref path)
               (walk/stringify-keys value)
               (reify-completion-listener
                 (fn [error snap]
                   (if error
                     (throw (ex-info "Error setting firebase value"
                                     {:path path}
                                     error))
                     (deliver p snap)))))
    @p))

(defn update-value [path value]
  (let [p (promise)]
    (.updateChildren (->ref path)
                     (walk/stringify-keys value)
                     (reify-completion-listener
                       (fn [error snap]
                         (if error
                           (throw (ex-info "Error updating firebase value"
                                           {:path path}
                                           error))
                           (deliver p snap)))))
    @p))

(defn custom-token
  ;; we can pass a custom token to the browser & use it to sign in to Firebase/Sparkboard
  ([uid] (.createCustomToken @auth uid))
  ([uid claims] (.createCustomToken @auth uid claims)))

(defn email->uid [email]
  (some-> (.getUserByEmail @auth email)
          (.getUid)))

(comment

  (time (set-value "/test" {:a {:b (rand-int 1000)
                                :c (rand-int 1000)}}))

  (time (update-value "/test" {"a/b" (rand-int 1000)})))
