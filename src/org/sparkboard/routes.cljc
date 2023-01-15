(ns org.sparkboard.routes
  (:require [bidi.bidi :as bidi]
            [org.sparkboard.server.resources :as-alias res]
            [org.sparkboard.client.views :as-alias views]
            [org.sparkboard.client.slack :as-alias slack.client]
            [org.sparkboard.client.auth :as-alias auth.client]
            [org.sparkboard.macros :refer [lazy-views]]
            #?(:cljs [shadow.lazy :as lazy])
            #?(:clj [org.sparkboard.server.views :as server.views]))
  #?(:cljs (:require-macros org.sparkboard.routes)))

(def routes
  "Route definitions. Routes are identified by their keyword-id. Options:

  :view  - symbol pointing to a (client) view for the single-page app
  :query - symbol pointing to a (server) function providing data for the route

  Views and queries are resolved lazily using shadow.lazy (cljs) and requiring-resolve (clj)
  This lets us require this namespace without causing circular dependencies."
  (lazy-views
   {:home {:route ["/"]
           :view `views/home}
    :dev/playground {:route ["/playground"]
                     :view `views/playground}
    :dev/skeleton {:route ["/skeleton"]
                   :view `views/skeleton}
    :slack/invite-offer {:route ["/slack/invite-offer"]
                         :view `slack.client/invite-offer}
    :slack/link-complete {:route ["/slack/link-complete"]
                          :view `slack.client/link-complete}
    :auth-test {:route ["/auth-test"]
                :view `auth.client/auth-header}
    :org/index {:route ["/o"]
                :query `res/org-index
                :view `views/org-index}
    :org/view {:route ["/o/" :org/id]
               :query `res/org-view
               :view `views/org-view}
    :list {:route ["/list"]
           :query `res/list-view
           :view `views/list-view}}))

(def bidi-routes
  ;; reformat the canonical route map into a bidi-compatible vector.
  ["" (->> routes
           (mapv (fn [[id {:keys [route view query]}]]
                   [route (bidi/tag
                           #?(:clj  (if query
                                      (requiring-resolve query)
                                      @server.views/spa-page)
                              :cljs view)
                           id)])))])

(def path-for
  (partial bidi/path-for bidi-routes))

(def match-route
  (partial bidi/match-route bidi-routes))

#?(:cljs
   (extend-protocol bidi/Matched
     lazy/Loadable
     (resolve-handler [this m] (bidi/succeed this m))
     (unresolve-handler [this m] (when (= this (:handler m)) ""))))