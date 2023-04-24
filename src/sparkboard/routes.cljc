(ns sparkboard.routes
  (:require #?(:cljs [sparkboard.query-params :as query-params])
            #?(:cljs [shadow.lazy :as lazy])
            #?(:cljs [vendor.pushy.core :as pushy])
            #?(:cljs [yawn.view :as v])
            #?(:cljs [yawn.hooks :as hooks])
            [bidi.bidi :as bidi]
            [re-db.api :as db]
            [re-db.reactive :as r]
            [sparkboard.client.slack :as-alias slack.client]
            [sparkboard.client.views :as-alias views]
            [sparkboard.server.db :as-alias server.db]
            [sparkboard.http :as http]
            [sparkboard.util :as u]
            [sparkboard.impl.routes :as impl :refer [E]])
  #?(:cljs (:require-macros [sparkboard.routes])))

(r/redef !routes
  "Route definitions.
  :view  - symbol pointing to a (client) view for the single-page app
  :query - symbol pointing to a (server) function providing data for the route
  :mutation - symbol pointing to a (server) function accepting a POST body
  :handler - symbol pointing to a (server) function accepting a request map"
  (r/atom ["/" {"" (E :home {:public true
                             :view `views/home})
                ["documents/" :file/name] (E :markdown/file
                                             {:handler 'sparkboard.server.core/serve-markdown
                                              :public true})
                "slack/" {"invite-offer" (E :slack/invite-offer
                                            {:view `slack.client/invite-offer})
                          "link-complete" (E :slack/link-complete
                                             {:view `slack.client/link-complete})}
                "login" (E :auth/sign-in {:view `views/account:sign-in
                                          :public true})
                "logout" (E :auth/logout {:handler 'sparkboard.server.auth/logout
                                          :public true})
                "oauth2" {"/google" (E :oauth2.google/launch {})
                          "/google/callback" (E :oauth2.google/callback {})
                          "/google/landing" (E :oauth2.google/landing
                                               {:public true
                                                :handler 'sparkboard.server.auth/oauth2-google-landing})}
                "v2" {"" (E :org/index
                            {:query `server.db/$org:index
                             :view `views/org-index})
                      "/login" (E :login
                                  {:public true
                                   :view `views/account:sign-in
                                   :mutation `server.db/login-handler})

                      "/o/create" (E :org/create
                                     {:view `views/org-create
                                      :mutation `server.db/org:create})
                      "/o/delete" (E :org/delete
                                     {:mutation `server.db/org:delete})
                      ["/o/" :org/id "/create-board"] (E :board/create
                                                         {:view `views/board:create
                                                          :mutation `server.db/board:create})
                      ["/o/" :org/id] (E :org/one
                                         {:query `server.db/$org:one
                                          :view `views/org:one})
                      ["/o/" :org/id "/search"] (E :org/search
                                                   {:query `server.db/$search})


                      ["/b/" :board/id "/projects/new"] (E :project/create
                                                           {:view `views/project:create
                                                            :mutation `server.db/project:create})
                      ["/b/" :board/id "/register"] (E :board/register
                                                       {:view `views/board:register
                                                        :mutation `server.db/board:register})
                      ["/b/" :board/id] (E :board/one
                                           {:query `server.db/$board:one
                                            :view `views/board:one})
                      ["/p/" :project/id] (E :project/one
                                             {:query `server.db/$project:one
                                              :view `views/project:one})
                      ["/m/" :member/id] (E :member/one
                                            {:query `server.db/$member:one
                                             :view `views/member:one})}}]))

(defn path-for
  "Given a route vector like `[:route/id {:param1 val1}]`, returns the path (string)"
  [route & {:as options}]
  (cond (string? route) route
        (keyword? route) (bidi/path-for @!routes route options)
        (vector? route) (apply bidi/path-for @!routes route)
        :else (bidi/path-for @!routes route options)))

(defn match-path
  "Resolves a path (string or route vector) to its handler map (containing :view, :query, etc.)"
  [path]
  (impl/match-route @!routes (path-for path)))


#?(:cljs
   (do
     (defonce !current-location (r/atom nil))

     (defn ready-view [view]
       (if (instance? lazy/Loadable view)
         (when (lazy/ready? view) @view)
         view))

     (defn set-location! [location]
       (db/transact! [[:db/retractEntity :env/location]
                      (assoc location :db/id :env/location)]))

     (defn handle-match [{:as match :keys [view]}]
       (if-let [view (ready-view view)]
         (set-location! (assoc match :view view))
         (lazy/load view
                    #(set-location! (assoc match :view %)))))

     (defonce history (pushy/pushy handle-match #(u/guard (#'impl/match-route @!routes %) :view)))

     (defn merge-query! [params]
       (let [{:keys [path query-params]} (query-params/merge-query (db/get :env/location :path) params)]
         (pushy/set-token! history path)
         query-params
         #_(swap! !current-location assoc :query-params query-params)))

     (defn use-view [route]
       (let [view (:view (match-path route))
             !p (hooks/use-ref nil)]
         (if-let [v (ready-view view)]
           v
           (if-let [p @!p]
             (throw p)
             (do (reset! !p (js/Promise.
                             (fn [resolve reject]
                               (lazy/load view resolve))))
                 (throw @!p))))))

     (defn set-path! [route]
       (pushy/set-token! history (path-for route)))))

(defn breadcrumb [path] (impl/breadcrumb @!routes path))

(defn path->route [path]
  (cond-> path
          (string? path)
          (->> (impl/match-route @!routes) :route)))

(defn mutate! [{:keys [route response-fn] :as opts} & args]
  (http/request (path-for route)
                (merge {:body (vec args)
                        :body/content-type :transit+json
                        :method "POST"}
                       ;; `response-fn`, if provided, should be a fn of two
                       ;; args [response url] and returning the response
                       ;; after doing whatever it needs to do.
                       (dissoc opts :body :route))))
