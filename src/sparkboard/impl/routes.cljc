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
  (let [path (normalize-slashes path)]
    (let [{:as m :keys [tag route-params]} (bidi/match-route routes path)]
      (if m
        (let [params (or route-params {})
              route [tag params]]
          (assoc @(:handler m)
            :tag tag
            :params (-> params
                        (u/assoc-some :query-params (not-empty (query-params/path->map path)))
                        (assoc :path path
                               :route route))))
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

#?(:clj
   (defn memo-fn-var [query-var]
     (memo/fn-memo [& args]
       (r/reaction
         (let [f (hooks/use-deref query-var)]
           (apply f args))))))

(defn dequote [form]
  (if (and (seq? form) (= 'quote (first form)))
    (second form)
    form))

(defmacro resolve-endpoint [endpoint]
  (let [aliases (ns-aliases *ns*)
        resolve-sym (fn [sym]
                      (if-let [resolved (get aliases (symbol (namespace sym)))]
                        (symbol (str resolved) (name sym))
                        sym))
        wrap-requiring-resolve (fn [s] `(requiring-resolve ~s))]
    (if (:ns &env)
      (u/update-some endpoint {:VIEW (fn [v] `(lazy/loadable ~(resolve-sym (second v))))})
      (-> endpoint
          (u/update-some {:QUERY wrap-requiring-resolve
                          :GET   wrap-requiring-resolve
                          :POST  wrap-requiring-resolve})
          (cond-> (:QUERY endpoint)
                  (assoc :$query `(memo-fn-var (requiring-resolve ~(:QUERY endpoint)))))))))

(defmacro E
  ;; wraps :view keys with lazy/loadable (and resolves aliases, with :as-alias support)
  [tag endpoint]
  `(~'bidi.bidi/tag
    (delay (resolve-endpoint ~endpoint))
    ~tag))

(defmacro wrap-endpoints [routes]
  (walk/postwalk (fn [x]
                   (if (and (map? x)
                            (:id x))
                     `(E ~(:id x) ~x)
                     x))
                 routes))