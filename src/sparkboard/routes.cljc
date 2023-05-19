(ns sparkboard.routes
  (:require #?(:cljs ["react" :as react])
            #?(:cljs [shadow.lazy :as lazy])
            #?(:cljs [vendor.pushy.core :as pushy])
            #?(:cljs [yawn.view :as v])
            #?(:cljs [yawn.hooks :as hooks])
            [applied-science.js-interop :as j]
            [bidi.bidi :as bidi :refer [uuid]]
            [promesa.core :as p]
            [sparkboard.transit :as t]
            [re-db.api :as db]
            [re-db.reactive :as r]
            [sparkboard.client.slack :as-alias slack.client]
            [sparkboard.client.views :as-alias views]
            [sparkboard.query-params :as query-params]
            [sparkboard.server.db :as-alias server.db]
            [sparkboard.http :as http]
            [sparkboard.util :as u]
            [sparkboard.impl.routes :as impl :refer [E]])
  #?(:cljs (:require-macros [sparkboard.routes])))

(def ENTITY-ID [bidi/uuid :entity/id])

(r/redef !routes
  "Route definitions.
  :view  - symbol pointing to a (client) view for the single-page app
  :query - symbol pointing to a (server) function providing data for the route
  :post - symbol pointing to a (server) function accepting a POST body
  :handler - symbol pointing to a (server) function accepting a request map"
  (r/atom ["" {"/" (E :home {:public true
                             :view `views/home})
               "/ws" (E :websocket {:public true
                                    :handler 'sparkboard.server.core/ws-handler})
               "/domain-availability" (E :domain-availability {:handler 'sparkboard.server.db/domain-availability})
               ["/documents/" :file/name] (E :markdown/file
                                             {:handler 'sparkboard.server.core/serve-markdown
                                              :public true})
               "/slack/" {"invite-offer" (E :slack/invite-offer
                                            {:view `slack.client/invite-offer})
                          "link-complete" (E :slack/link-complete
                                             {:view `slack.client/link-complete})}
               "/login" (E :account/sign-in {:view `views/account:sign-in
                                             :header? false
                                             :post 'sparkboard.server.accounts/login-handler
                                             :public true})
               "/logout" (E :account/logout {:handler 'sparkboard.server.accounts/logout-handler
                                             :public true})
               "/locale/set" (E :account/set-locale {:post 'sparkboard.i18n/set-locale})
               "/oauth2" {"/google" {"/launch" (E :oauth2.google/launch {})
                                     "/callback" (E :oauth2.google/callback {})
                                     "/landing" (E :oauth2.google/landing
                                                   {:public true
                                                    :handler 'sparkboard.server.accounts/google-landing})}}
               "/o" {"/index" (E :org/index
                                 {:query `server.db/$org:index
                                  :view `views/org:index})
                     "/new" (E :org/new
                               {:view `views/org:new
                                :post `server.db/org:new})
                     ["/" [bidi/uuid :org]] {"" (E :org/view
                                                   {:query `server.db/$org:view
                                                    :view `views/org:view})
                                             "/settings" (E :org/settings
                                                            {:view `views/org:settings
                                                             :query `server.db/$org:settings
                                                             :post `server.db/org:settings})
                                             "/delete" (E :org/delete
                                                          {:post `server.db/org:delete})
                                             "/boards/new" (E :board/new
                                                              {:view `views/board:new
                                                               :post `server.db/board:new})
                                             "/search" (E :org/search
                                                          {:query `server.db/$org:search})}}
               ["/b/" [bidi/uuid :board]] {"" (E :board/view
                                                 {:query `server.db/$board:view
                                                  :view `views/board:view})
                                           "/new-project" (E :project/new
                                                             {:view `views/project:new
                                                              :post `server.db/project:new})
                                           "/register" (E :board/register
                                                          {:view `views/board:register
                                                           :post `server.db/board:register})}
               ["/p/" [bidi/uuid :project]] (E :project/view
                                               {:query `server.db/$project:view
                                                :view `views/project:view})
               ["/m/" [bidi/uuid :member]] (E :member/view
                                              {:query `server.db/$member:view
                                               :view `views/member:view})}]))

(defn path-for
  "Given a route vector like `[:route/id {:param1 val1}]`, returns the path (string)"
  [route & {:as options}]
  (cond-> (cond (string? route) route
                (keyword? route) (bidi/path-for @!routes route options)
                (vector? route) (apply bidi/path-for @!routes route)
                :else (bidi/path-for @!routes route options))
          (:query options)
          (-> (query-params/merge-query (:query options)) :path)))

(defn entity [{:as e :entity/keys [kind id]} key]
  (when e

    (let [tag (keyword (name kind) (name key))]
      (path-for tag kind id))))


(defn match-path
  "Resolves a path (string or route vector) to its handler map (containing :view, :query, etc.)"
  [path]
  (impl/match-route @!routes (path-for path)))

#?(:cljs
   (do

     (defn set-location! [location]
       (db/transact! [[:db/retractEntity :env/location]
                      (assoc location :db/id :env/location)]))

     (defonce history (pushy/pushy set-location! #(u/guard (#'impl/match-route @!routes %) :view)))

     (defn merge-query! [params]
       (let [{:keys [path query-params]} (query-params/merge-query (db/get :env/location :path) params)]
         (pushy/set-token! history path)
         query-params))

     (defn set-path! [& args]
       (pushy/set-token! history (apply path-for args)))))

(defn breadcrumb [path] (impl/breadcrumb @!routes path))

(defn path->route [path]
  (cond-> path
          (string? path)
          (->> (impl/match-route @!routes) :route)))

#?(:cljs
   (defn POST [route & argv]
     (-> (js/fetch (path-for route)
                   (j/lit {:headers {"Accept" "application/transit+json"
                                     "Content-type" "application/transit+json"}
                           :body (t/write (vec argv))
                           :method "POST"}))
         (.then http/format-response))))

#?(:cljs
   (defn GET [route & args]
     (-> (js/fetch (apply path-for route args)
                   (j/lit {:headers {"Accept" "application/transit+json"
                                     "Content-type" "application/transit+json"}
                           :method "GET"}))
         (.then http/format-response))))
