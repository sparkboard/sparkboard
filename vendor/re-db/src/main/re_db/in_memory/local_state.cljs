(ns re-db.in-memory.local-state
  (:require [re-db.in-memory :as db]
            [re-db.api :as api]
            [re-db.reactive :as r]
            [re-db.read :as read]
            [re-db.util :as util])
  (:require-macros re-db.in-memory.local-state))

;; an entity-atom always causes a dependency on the "whole entity"

(defn- resolve-conn [conn]
  (if (keyword? conn) (api/conn) conn))

(deftype EAtom [conn e default]
  IDeref
  (-deref [this]
    (read/get (resolve-conn conn) ::local-state e default))
  IReset
  (-reset! [this new-value]
    (read/transact! (resolve-conn conn) [[:db/add ::local-state e new-value]]))
  ISwap
  (-swap! [this f] (reset! this (f @this)))
  (-swap! [this f a] (reset! this (f @this a)))
  (-swap! [this f a b] (reset! this (f @this a b)))
  (-swap! [this f a b xs] (reset! this (apply f @this a b xs))))

(defn snapshot
  "Read value of EAtom from a db snapshot (point-in-time)"
  [e-atom db]
  (get (db/get-entity db ::local-state) (.-e e-atom)))

;; a string key where we'll memoize the cursor per-component-instance
(def ratom-cache-key (str ::local-state))

(defn local-state*
  "Return a local-state cursor for a Reagent component.
    :key      - to differentiate instances of this component
    :default  - initial value"
  [owner & {:keys [key default location conn id]
            :or {key :singleton
                 conn :re-db.api/conn}}]
  (let [e (or id {location key})]
    (r/add-on-dispose! owner
                       (fn [_]
                         (read/transact! conn [[:db/retract ::local-state e]])))
    (EAtom. conn e default)))