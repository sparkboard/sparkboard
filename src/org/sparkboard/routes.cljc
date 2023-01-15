(ns org.sparkboard.routes
  (:require [bidi.bidi :as bidi]
            [clojure.walk :as walk]
            [org.sparkboard.server.resources :as-alias res]
            [org.sparkboard.client.views :as-alias views]
            #?(:cljs [shadow.lazy :as lazy]))
  #?(:cljs (:require-macros org.sparkboard.routes)))

(defmacro lazy-browse
  ;; wraps :browse keys with lazy/loadable (and resolves aliases, with :as-alias support)
  [expr]
  (let [aliases (ns-aliases *ns*)
        resolve-sym (fn [sym]
                      ;; resolve using ns-aliases including :as-alias
                      (if-let [resolved (get aliases (symbol (namespace sym)))]
                        (symbol (str resolved) (name sym))
                        sym))]
    (walk/postwalk
     (fn [x]
       (if (and (map? x) (contains? x :browse))
         (if (:ns &env)
           (let [sym (resolve-sym (second (:browse x)))]
             (assoc x :browse `(lazy/loadable ~sym)))
           (dissoc x :browse))
         x))
     expr)))

(def routes
  [["/" {:name :home}]
   ["/playground" {:name :playground}]
   ["/skeleton" {:name :skeleton}]
   ["/slack/invite-offer" {:name :slack/invite-offer}]
   ["/slack/link-complete" {:name :slack/link-complete}]
   ["/auth-test" {:name :auth-test}]])

(def resources
  ;; define resources by id.
  ;; :ref-fn resolves data,
  ;; :browse points to client views.
  (org.sparkboard.routes/lazy-browse
   {:org/index {:route ["/o"]
                :ref-fn `res/org-index
                :browse `views/org-index}
    :org/view {:route ["/o/" :org/id]
               :ref-fn `res/org-view
               :browse `views/org-view}
    :list {:route ["/list"]
           :ref-fn `res/list-view
           :browse `views/list-view}}))

#?(:clj
   (def server-routes
     ["" (mapv (fn [[_ {:keys [route ref-fn]}]] [route (requiring-resolve ref-fn)]) resources)])
   :cljs
   (def client-routes
     (into ["" (mapv (fn [[_ {:keys [route browse]}]] [route browse]) resources)]
           routes)))

(def path-routes ["" (mapv (fn [[id {:keys [route]}]] [route id]) resources)])

(def path-for
  (partial bidi/path-for path-routes))

(def match-route
  (partial bidi/match-route #?(:cljs client-routes :clj server-routes)))