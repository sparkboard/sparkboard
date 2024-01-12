(ns sb.client.local-storage
  (:require [applied-science.js-interop :as j]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.sync.transit :as transit]))

#?(:cljs
   (defn- set-item [k v]
     {:pre [(string? k)]}
     (j/call-in js/window [:localStorage :setItem] k (transit/pack v))))

#?(:cljs
   (defn- get-item [k]
     {:pre [(string? k)]}
     (transit/unpack (j/call-in js/window [:localStorage :getItem] k))))

(comment
  (get-item "foo")
  (set-item "foo" 1)
  (.. js/window -localStorage (getItem "foo"))
  (.. js/window -localStorage (setItem "foo" "bar"))

  )

(memo/defn-memo $local-storage
  "Returns a 2-way syncing local-storage atom identified by `k` with default value"
  [k default]
  #?(:cljs
     (let [k (str k)]
       (doto (r/atom (or (get-item k)
                         (doto default (->> (set-item k)))))
         (add-watch ::update-local-storage
                    (fn [_k _atom _old v] (set-item k v)))))
     :clj (r/atom default)))

