(ns sparkboard.impl.routes
  (:require [bidi.bidi :as bidi]
            [clojure.string :as str]
            [sparkboard.util :as u]
            [sparkboard.query-params :as query-params]
            [shadow.lazy #?(:clj :as-alias :cljs :as) lazy])
  #?(:cljs (:require-macros sparkboard.impl.routes)))

(defn map->bidi [routes]
  ["" (->> routes
           (mapv (fn [[id {:as result :keys [route view query]}]]
                   [route (bidi/tag
                           (delay
                            #?(:clj  (-> result
                                         (u/update-some {:query requiring-resolve
                                                         :mutation requiring-resolve
                                                         :handler requiring-resolve}))
                               :cljs result))
                           id)])))])

(defn normalize-slashes [path]
  ;; remove trailing /'s, but ensure path starts with /
  (-> path
      (cond-> (and (> (count path) 1) (str/ends-with? path "/"))
              (subs 0 (dec (count path))))
      (u/ensure-prefix "/")))

(defn match-route [routes path]
  (let [path (normalize-slashes path)]
    (let [{:as m :keys [view tag route-params]} (bidi/match-route routes path)]
      (if m
        (let [params (u/assoc-some (or route-params {})
                       :query-params (not-empty (query-params/path->map path)))
              match @(:handler m)]
          (merge
           match
           {:tag tag
            :path path
            :route [tag params]
            :params params}))
        (prn :no-match! path)))))

#?(:cljs
   (extend-protocol bidi/Matched
     lazy/Loadable
     (resolve-handler [this m] (bidi/succeed this m))
     (unresolve-handler [this m] (when (= this (:handler m)) ""))))

(extend-protocol bidi/Matched
  #?(:cljs Delay :clj clojure.lang.Delay)
  (resolve-handler [this m] (bidi/succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) "")))

(defn breadcrumb [routes path]
  (->> (iteration #(second (re-find #"(.*)/.*$" (or % "")))
                  :initk path)
       (keep (comp :route (partial match-route routes)))))

(defmacro E
  ;; wraps :view keys with lazy/loadable (and resolves aliases, with :as-alias support)
  [tag endpoint]
  (let [aliases (ns-aliases *ns*)
        resolve-sym (fn [sym]
                      (if-let [resolved (get aliases (symbol (namespace sym)))]
                        (symbol (str resolved) (name sym))
                        sym))]
    `(~'bidi.bidi/tag
      (delay
       ~(u/update-some endpoint (if (:ns &env)
                                  {:view (fn [v] `(lazy/loadable ~(resolve-sym (second v))))}
                                  {:query (fn [s] `(requiring-resolve ~s))
                                   :mutation (fn [s] `(requiring-resolve ~s))
                                   :handler (fn [s] `(requiring-resolve ~s))})))
      ~tag)))