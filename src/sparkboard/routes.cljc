(ns sparkboard.routes
  (:require #?(:cljs ["react" :as react])
            #?(:cljs [shadow.lazy :as lazy])
            #?(:cljs [vendor.pushy.core :as pushy])
            #?(:cljs [yawn.view :as v])
            #?(:cljs [yawn.hooks :as hooks])
            [applied-science.js-interop :as j]
            [bidi.bidi :as bidi]
            [sparkboard.transit :as t]
            [re-db.api :as db]
            [re-db.reactive :as r]
            [sparkboard.client.slack :as-alias slack.client]
            [sparkboard.client.views :as-alias views]
            [sparkboard.query-params :as query-params]
            [sparkboard.entities.board :as-alias board]
            [sparkboard.entities.domain :as-alias domain]
            [sparkboard.entities.member :as-alias member]
            [sparkboard.entities.org :as-alias org]
            [sparkboard.entities.project :as-alias project]
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
  (r/reaction
    ["" {"/" (E :home {:public true
                       :view `views/home})
         "/ws" (E :websocket {:public true
                              :handler 'sparkboard.server.core/ws-handler})

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
         "/locale/set" (E :account/set-locale {:post 'sparkboard.i18n/set-locale-response})
         "/oauth2" {"/google" {"/launch" (E :oauth2.google/launch {})
                               "/callback" (E :oauth2.google/callback {})
                               "/landing" (E :oauth2.google/landing
                                             {:public true
                                              :handler 'sparkboard.server.accounts/google-landing})}}

         "/domain-availability" (E :domain/availability
                                   {:handler `domain/availability})
         "/o" {"/index" (E :org/index
                           {:query `org/$index$query
                            :view `org/index$view})
               "/new" (E :org/new
                         {:view `org/new$view
                          :post `org/new$post})
               ["/" [bidi/uuid :org]] {"" (E :org/read
                                             {:query `org/$read:query
                                              :view `org/read:view})
                                       "/settings" (E :org/settings
                                                      {:view `org/settings:view
                                                       :query `org/$settings:query
                                                       :post `org/settings:post})
                                       "/delete" (E :org/delete
                                                    {:post `org/delete:post})
                                       "/new-board" (E :org/new-board
                                                       {:view `board/new:view
                                                        :post `board/new:post})
                                       "/search" (E :org/search
                                                    {:query `org/search:query})}}
         ["/b/" [bidi/uuid :board]] {"" (E :board/read
                                           {:query `board/$read:query
                                            :view `board/read:view})
                                     "/new-project" (E :project/new
                                                       {:view `project/new:view
                                                        :post `project/new:post})
                                     "/register" (E :board/register
                                                    {:view `board/register:view
                                                     :post `board/register:post})}
         ["/p/" [bidi/uuid :project]] {"" (E :project/read
                                             {:query `project/$read:query
                                              :view `project/read:view})}
         ["/m/" [bidi/uuid :member]] {"" (E :member/read
                                            {:query `member/$read:query
                                             :view `member/read:view})}}]))

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

(defn tag [tag endpoint]
  (bidi/tag (delay endpoint) tag))