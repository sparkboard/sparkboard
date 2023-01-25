(ns org.sparkboard.routes
  (:require [applied-science.js-interop :as j]
            [bidi.bidi :as bidi]
            [clojure.string :as str]
            [org.sparkboard.server.queries :as-alias queries]
            [org.sparkboard.client.views :as-alias views]
            [org.sparkboard.client.slack :as-alias slack.client]
            [org.sparkboard.client.auth :as-alias auth.client]
            [org.sparkboard.macros :refer [lazy-views]]
            [re-db.reactive :as r]
            #?(:cljs [tools.sparkboard.browser.query-params :as query-params])
            #?(:cljs [pushy.core :as pushy])
            #?(:cljs [shadow.lazy :as lazy])
            #?(:clj [org.sparkboard.server.views :as server.views])
            #?(:clj [org.sparkboard.server.env :as env]))
  #?(:cljs (:require-macros org.sparkboard.routes)))

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

            ;; :org/search {:route ["/search"]
            ;;              :query `queries/$search}

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

            :board/index {:route ["/b"] ;; XXX cruft?
                          :query `queries/$board:index}
            :board/one {:route ["/b/" :board/id]
                        :query `queries/$board:one
                        :view `views/board:one}
            :project/index {:route ["/p"] ;; XXX cruft?
                            :query `queries/$project:index}
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
            (mapv (fn [[id {:keys [route view query]}]]
                    [route (bidi/tag
                            #?(:clj  (if query
                                       (requiring-resolve query)
                                       (server.views/spa-page env/client-config))
                               :cljs view)
                            id)])))]))

(defn path-for [handler & params]
  (apply bidi/path-for @!bidi-routes handler params))

(defn match-route [path]
  (some-> (bidi/match-route @!bidi-routes path)
          (assoc :path path)))

#?(:cljs
   (extend-protocol bidi/Matched
     lazy/Loadable
     (resolve-handler [this m] (bidi/succeed this m))
     (unresolve-handler [this m] (when (= this (:handler m)) ""))))

#?(:cljs
   (do
     (defonce !current-location (atom nil))

     (defn as-url ^js [route]
       (if (instance? js/URL route)
         route
         (new js/URL route "https://example.com")))

     (defonce history (pushy/pushy
                       (fn [{:as match handler :handler}]
                         (if (instance? lazy/Loadable handler)
                           (lazy/load handler
                                      (fn [handler]
                                        (reset! !current-location (assoc match
                                                                    :query-params (query-params/path->map
                                                                                   (:path match))
                                                                    :handler handler))))
                           (reset! !current-location match)))
                       (fn [path]
                         (match-route path))))

     (defn merge-query! [params]
       (let [{:keys [path query-params]} (query-params/merge-query (:path @!current-location) params)]
         (pushy/set-token! history path)
         (swap! !current-location assoc :query-params query-params)))))
