(ns sparkboard.impl.routes
  (:require [bidi.bidi :as bidi]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [sparkboard.util :as u]
            [re-db.memo :as memo]
            [re-db.reactive :as r]
            [re-db.hooks :as hooks]
            [sparkboard.query-params :as query-params]
            [shadow.lazy #?(:clj :as-alias :cljs :as) lazy])
  #?(:cljs (:require-macros sparkboard.impl.routes)))

(defn normalize-slashes [path]
  ;; remove trailing /'s, but ensure path starts with /
  (-> path
      (cond-> (and (> (count path) 1) (str/ends-with? path "/"))
              (subs 0 (dec (count path))))
      (u/ensure-prefix "/")))

(defn match-route [routes path]
  (when-let [path (some-> path normalize-slashes)]
    (let [{:as m :keys [route-params endpoints]} (bidi/match-route routes path)
          params (-> (or route-params {})
                     (u/assoc-some :query-params (not-empty (query-params/path->map path))))]
      (if m
        {:match/endpoints endpoints
         :match/path      path
         :match/params    params}
        (prn :no-match! path)))))

(defn breadcrumb [routes path]
  (->> (iteration #(second (re-find #"(.*)/.*$" (or % "")))
                  :initk path)
       (keep (comp :route (partial match-route routes)))))

#?(:clj
   (defn memo-fn-var [query-var]
     (memo/fn-memo [& args]
       (r/reaction
         (let [f (hooks/use-deref query-var)]
           (apply f args))))))

