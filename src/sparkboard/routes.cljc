(ns sparkboard.routes
  (:require #?(:cljs [sparkboard.query-params :as query-params])
            #?(:cljs [shadow.lazy :as lazy])
            #?(:cljs [vendor.pushy.core :as pushy])
            #?(:cljs [yawn.view :as v])
            #?(:cljs [yawn.hooks :as hooks])
            [bidi.bidi :as bidi]
            [sparkboard.client.auth :as-alias auth.client]
            [sparkboard.client.slack :as-alias slack.client]
            [sparkboard.client.views :as-alias views]
            [sparkboard.macros :refer [E]]
            [sparkboard.server.db :as-alias db]
            [re-db.reactive :as r]
            [sparkboard.http :as sb.http]
            [sparkboard.util :as u]
            [sparkboard.impl.routes :as impl])
  #?(:cljs (:require-macros [sparkboard.routes])))

(r/redef !routes
  "Route definitions.
  :view  - symbol pointing to a (client) view for the single-page app
  :query - symbol pointing to a (server) function providing data for the route
  :mutation - symbol pointing to a (server) function accepting a POST body
  :handler - symbol pointing to a (server) function accepting a request map"
  (r/atom ["/" {"" (E :home {:public? true
                             :view `views/home})
                "slack/" {"invite-offer" (E :slack/invite-offer
                                            {:view `slack.client/invite-offer})
                          "link-complete" (E :slack/link-complete
                                             {:view `slack.client/link-complete})}
                "logout" (E :auth/logout {:handler 'sparkboard.server.auth/logout
                                          :public? true})
                "oauth2" {"/google" (E :oauth2.google/launch {})
                          "/google/callback" (E :oauth2.google/callback {})
                          "/google/landing" (E :oauth2.google/landing
                                              {:public? true
                                               :handler 'sparkboard.server.auth/oauth2-google-landing})}
                "v2" {"" (E :org/index
                            {:query `db/$org:index
                             :view `views/org:index})
                      "/login" (E :login
                                  {:public? true
                                   :view `views/login
                                   :mutation `db/login-handler})

                      "/o/create" (E :org/create
                                     {:view `views/org:create
                                      :mutation `db/org:create})
                      "/o/delete" (E :org/delete
                                     {:mutation `db/org:delete})
                      ["/o/" :org/id "/create-board"] (E :board/create
                                                         {:view `views/board:create
                                                          :mutation `db/board:create})
                      ["/o/" :org/id] (E :org/one
                                         {:query `db/$org:one
                                          :view `views/org:one})
                      ["/o/" :org/id "/search"] (E :org/search
                                                   {:query `db/$search})


                      ["/b/" :board/id "/create-project"] (E :project/create
                                                             {:view `views/project:create
                                                              :mutation `db/project:create})
                      ["/b/" :board/id "/create-member"] (E :member/create
                                                            {:view `views/member:create
                                                             :mutation `db/member:create})
                      ["/b/" :board/id] (E :board/one
                                           {:query `db/$board:one
                                            :view `views/board:one})
                      ["/p/" :project/id] (E :project/one
                                             {:query `db/$project:one
                                              :view `views/project:one})
                      ["/m/" :member/id] (E :member/one
                                            {:query `db/$member:one
                                             :view `views/member:one})}}]))

(defn path-for [route]
  (if (string? route)
    route
    (apply bidi/path-for @!routes route)))

(defn match-route [path]
  (impl/match-route @!routes (path-for path)))

(comment
 (match-route [:board/one {:board/id "x"}]))

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

     (defonce history (pushy/pushy handle-match #(u/guard (#'impl/match-route @!routes %) :view)))

     (defn merge-query! [params]
       (let [{:keys [path query-params]} (query-params/merge-query (:path @!current-location) params)]
         (pushy/set-token! history path)
         query-params
         #_(swap! !current-location assoc :query-params query-params)))

     (defn use-view [route]
       (let [view (:view (match-route route))
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
       (pushy/set-token! history (path-for route)))

     ))

(defn breadcrumb [path] (impl/breadcrumb @!routes path))

(defn path->route [path]
  (cond-> path
          (string? path)
          (->> (impl/match-route @!routes) :route)))

(defn mutate! [{:keys [route response-fn] :as opts} & args]
  (sb.http/request (path-for route)
                   (merge {:body (vec args)
                           :body/content-type :transit+json
                           :method "POST"}
                          ;; `response-fn`, if provided, should be a fn of two
                          ;; args [response url] and returning the response
                          ;; after doing whatever it needs to do.
                          (dissoc opts :body :route))))
