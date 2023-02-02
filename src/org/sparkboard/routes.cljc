(ns org.sparkboard.routes
  (:require [bidi.bidi :as bidi]
            [org.sparkboard.server.queries :as-alias queries]
            [org.sparkboard.client.views :as-alias views]
            [org.sparkboard.client.slack :as-alias slack.client]
            [org.sparkboard.client.auth :as-alias auth.client]
            [org.sparkboard.macros :refer [lazy-views]]
            [re-db.reactive :as r]
            [tools.sparkboard.util :as u]
            #?(:cljs [tools.sparkboard.browser.query-params :as query-params])
            #?(:cljs [shadow.lazy :as lazy])
            #?(:cljs [vendor.pushy.core :as pushy]))
  #?(:cljs (:require-macros org.sparkboard.routes)))


#?(:clj
   (defn mutate-query-fn [body]
     (merge body
            {:qux "qux"}
            {:merged? "merged"})))

(r/redef !routes
  "Route definitions. Routes are identified by their keyword-id. Options:

  :view  - symbol pointing to a (client) view for the single-page app
  :query - symbol pointing to a (server) function providing data for the route

  Views and queries are resolved lazily using shadow.lazy (cljs) and requiring-resolve (clj)
  This lets us require this namespace without causing circular dependencies."
  (r/atom (lazy-views
           {:home {:route ["/"]
                   :view `views/home}
            :slack/invite-offer {:route ["/slack/invite-offer"]
                                 :view `slack.client/invite-offer}
            :slack/link-complete {:route ["/slack/link-complete"]
                                  :view `slack.client/link-complete}
            :auth-test {:route ["/auth-test"]
                        :view `auth.client/auth-header}

            ;; FIXME
            :mutate {:route ["/mutate"]
                     :mutation `mutate-query-fn}

            :board/create {:route ["/o/" :org/id "/create-board"]
                           :view `views/board:create
                           :mutation `queries/board:create}

            ;; Skeleton entry point is the full list of orgs
            :org/index {:route ["/skeleton"]
                        :query `queries/$org:index
                        :view `views/org:index}
            ;; Rest of the skeleton:
            :org/one {:route ["/o/" :org/id]
                      :query `queries/$org:one
                      :view `views/org:one}
            :org/search {:route ["/o/" :org/id "/search"]
                         :query `queries/$search}

            :board/one {:route ["/b/" :board/id]
                        :query `queries/$board:one
                        :view `views/board:one}
            :project/one {:route ["/p/" :project/id]
                          :query `queries/$project:one
                          :view `views/project:one}
            ;; member view
            :member/one {:route ["/m/" :member/id]
                         :query `queries/$member:one
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

(defn path-for [handler & params]
  (apply bidi/path-for @!bidi-routes handler params))

(defn match-route [path]
  (when-let [{:as m :keys [tag route-params]} (bidi/match-route @!bidi-routes path)]
    (let [params (u/assoc-some (or route-params {})
                   :query-params (not-empty #?(:cljs (query-params/path->map path)
                                               :clj nil)))]
      (merge
       @(:handler m)
       {:tag tag
        :path path
        :route [tag params]
        :params params}))))

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
     (defonce !current-route (atom nil))

     (defn as-url ^js [route]
       (if (instance? js/URL route)
         route
         (new js/URL route "https://example.com")))

     (defn handle-match [{:as match :keys [view]}]
       (let [loadable? (instance? lazy/Loadable view)
             ready-view (if loadable?
                          (when (lazy/ready? view) @view)
                          view)]
         (if ready-view
           (reset! !current-route (assoc match :view ready-view))
           (lazy/load view
                      #(reset! !current-route (assoc match :view %))))))

     (defonce history (pushy/pushy handle-match match-route))

     (defn merge-query! [params]
       (let [{:keys [path query-params]} (query-params/merge-query (:path @!current-route) params)]
         (pushy/set-token! history path)
         query-params
         #_(swap! !current-route assoc :query-params query-params)))))

(defn path->route [path]
  (cond-> path
          (string? path)
          (-> match-route :route)))