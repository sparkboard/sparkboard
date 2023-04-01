(ns org.sparkboard.routes
  (:require [bidi.bidi :as bidi]
            [clojure.string :as str]
            [org.sparkboard.server.db :as-alias db]
            [org.sparkboard.client.views :as-alias views]
            [org.sparkboard.client.slack :as-alias slack.client]
            [org.sparkboard.client.auth :as-alias auth.client]
            [org.sparkboard.macros :refer [lazy-views]]
            [re-db.reactive :as r]
            [tools.sparkboard.util :as u]
            [tools.sparkboard.http :as sb.http]
            #?(:cljs [tools.sparkboard.browser.query-params :as query-params])
            #?(:cljs [shadow.lazy :as lazy])
            #?(:cljs [vendor.pushy.core :as pushy])
            #?(:cljs [yawn.view :as v])
            #?(:cljs [yawn.hooks :as hooks]))
  #?(:cljs (:require-macros org.sparkboard.routes)))

(r/redef !routes
  "Route definitions. Routes are identified by their keyword-id. Options:

  :view  - symbol pointing to a (client) view for the single-page app
  :query - symbol pointing to a (server) function providing data for the route

  Views and queries are resolved lazily using shadow.lazy (cljs) and requiring-resolve (clj)
  This lets us require this namespace without causing circular dependencies."
  (r/atom (lazy-views
           {:home {:route ["/"]
                   :public? true
                   :view `views/home}
            :slack/invite-offer {:route ["/slack/invite-offer"]
                                 :view `slack.client/invite-offer}
            :slack/link-complete {:route ["/slack/link-complete"]
                                  :view `slack.client/link-complete}
            :auth-test {:route ["/auth-test"]
                        :view `auth.client/auth-header}

            :org/create {:route "/v2/o/create"
                         :view `views/org:create
                         :mutation `db/org:create}
            :org/delete {:route "/v2/o/delete"
                         :mutation `db/org:delete}
            :board/create {:route ["/v2/o/" :org/id "/create-board"]
                           :view `views/board:create
                           :mutation `db/board:create}
            :project/create {:route ["/v2/b/" :board/id "/create-project"]
                             :view `views/project:create
                             :mutation `db/project:create}
            :member/create {:route ["/v2/b/" :board/id "/create-member"]
                            :view `views/member:create
                            :mutation `db/member:create}

            ;; Skeleton entry point is the full list of orgs
            :org/index {:route ["/v2"]
                        :query `db/$org:index
                        :view `views/org:index}
            :login {:route ["/v2/login"]
                    :public? true
                    :view `views/login
                    :mutation `db/login-handler}
            :logout {:route ["/v2/logout"]
                     :public? true
                     :mutation `db/logout-handler}

            ;; Rest of the skeleton:
            :org/one {:route ["/v2/o/" :org/id]
                      :query `db/$org:one
                      :view `views/org:one}
            :org/search {:route ["/v2/o/" :org/id "/search"]
                         :query `db/$search}

            :board/one {:route ["/v2/b/" :board/id]
                        :query `db/$board:one
                        :view `views/board:one}
            :project/one {:route ["/v2/p/" :project/id]
                          :query `db/$project:one
                          :view `views/project:one}
            ;; member view
            :member/one {:route ["/v2/m/" :member/id]
                         :query `db/$member:one
                         :view `views/member:one}})))

;; NOTE
;; path params may include a special `:body` key which should correspond to
;; to the body of an HTTP request (e.g. POST).

;; TODO
;; a "skeleton-view" which fetches the query for the view and pprint's it,
;; later potentially upgrade pprint to a nested data structure which uses
;; the re-db schema to allow link-following and other cool stuff

(r/redef !bidi-routes
  (r/reaction
   ;; reformat the canonical route map into a bidi-compatible vector.
   ["" (->> @!routes
            (mapv (fn [[id {:as result :keys [route view query]}]]
                    [route (bidi/tag
                            (delay
                             #?(:clj  (-> result
                                          (u/update-some {:query requiring-resolve
                                                          :mutation requiring-resolve}))
                                :cljs result))
                            id)])))]))

(defn path-for [route]
  (apply bidi/path-for @!bidi-routes route))

(defn normalize-slashes [path]
  ;; remove trailing /'s, but ensure path starts with /
  (-> path
      (cond-> (and (> (count path) 1) (str/ends-with? path "/"))
              (subs 0 (dec (count path))))
      (u/ensure-prefix "/")))

(defn match-route [path]
  (let [path (normalize-slashes path)]
    (when-let [{:as m :keys [tag route-params]} (bidi/match-route @!bidi-routes path)]
      (let [params (u/assoc-some (or route-params {})
                     :query-params (not-empty #?(:cljs (query-params/path->map path)
                                                 :clj  nil)))]
        (merge
         @(:handler m)
         {:tag tag
          :path path
          :route [tag params]
          :params params})))))

#?(:cljs
   (extend-protocol bidi/Matched
     lazy/Loadable
     (resolve-handler [this m] (bidi/succeed this m))
     (unresolve-handler [this m] (when (= this (:handler m)) ""))))

(extend-protocol bidi/Matched
  #?(:cljs Delay :clj clojure.lang.Delay)
  (resolve-handler [this m] (bidi/succeed this m))
  (unresolve-handler [this m] (when (= this (:handler m)) "")))

#?(:cljs
   (do
     (defonce !current-location (atom nil))

     (defn as-url ^js [route]
       (if (instance? js/URL route)
         route
         (new js/URL route "https://example.com")))

     (defn ready-view [view]
       (if (instance? lazy/Loadable view)
         (when (lazy/ready? view) @view)
         view))

     (defn handle-match [{:as match :keys [view]}]
       (if-let [view (ready-view view)]
         (reset! !current-location (assoc match :view view))
         (lazy/load view
                    #(reset! !current-location (assoc match :view %)))))

     (defonce history (pushy/pushy handle-match match-route))

     (defn merge-query! [params]
       (let [{:keys [path query-params]} (query-params/merge-query (:path @!current-location) params)]
         (pushy/set-token! history path)
         query-params
         #_(swap! !current-location assoc :query-params query-params)))

     (defn use-view [view]
       (let [view (if (keyword? view)
                    (get-in @!routes [view :view])
                    view)
             !p (hooks/use-ref nil)]
         (if-let [v (ready-view view)]
           v
           (if-let [p @!p]
             (throw p)
             (do (reset! !p (js/Promise.
                             (fn [resolve reject]
                               (lazy/load view resolve))))
                 (throw @!p))))))

     (defn set-location! [route]
       (set! (.-location js/window)
             (path-for route)))))


(defn breadcrumb [path]
  (->> (iteration #(second (re-find #"(.*)/.*$" (or % "")))
                  :initk path)
       (keep (comp :route match-route))))

(defn path->route [path]
  (cond-> path
          (string? path)
          (-> match-route :route)))

(defn mutate! [{:keys [route response-fn] :as opts} & args]
  (sb.http/request (path-for route)
                   (merge {:body (vec args)
                           :body/content-type :transit+json
                           :method "POST"}
                          ;; `response-fn`, if provided, should be a fn of two
                          ;; args [response url] and returning the response
                          ;; after doing whatever it needs to do.
                          (dissoc opts :body :route))))
