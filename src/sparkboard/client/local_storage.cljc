(ns sparkboard.client.local-storage
  (:require [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.sync.transit :as transit]))

(memo/defn-memo $local-storage
                "Returns a 2-way syncing local-storage atom identified by `k` with default value"
                [k default]
  #?(:cljs
     (let [k (str k)]
       (doto (r/atom (or (-> (.-localStorage js/window)
                             (.getItem k)
                             transit/unpack)
                         default))
         (add-watch ::update-local-storage
                    (fn [_k _atom _old new]
                      (.setItem (.-localStorage js/window)
                                k
                                (transit/pack new))))))
     :clj (r/atom default)))
 