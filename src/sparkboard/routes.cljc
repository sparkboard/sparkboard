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
            [re-db.hooks :as rh]
            [sparkboard.client.slack :as-alias slack.client]
            [sparkboard.client.views :as-alias views]
            [sparkboard.query-params :as query-params]
            [sparkboard.entities.board :as-alias board]
            [sparkboard.entities.domain :as-alias domain]
            [sparkboard.entities.member :as-alias member]
            [sparkboard.entities.org :as-alias org]
            [sparkboard.entities.project :as-alias project]
            [sparkboard.entities.account :as-alias account]
            [sparkboard.http :as http]
            [sparkboard.util :as u]
            [sparkboard.impl.routes :as impl :refer [E]])
  #?(:cljs (:require-macros [sparkboard.routes])))

(def ENTITY-ID [bidi/uuid :entity/id])

(r/redef !routes
  "Route definitions.
  :view     - symbol pointing to a (client) view for the single-page app
  :query    - symbol pointing to a (server) function providing data for the route
  :POST     - symbol pointing to a (server) function accepting [req, params & body]
  :GET      - symbol pointing to a (server) function accepting a request map"
  (r/reaction
    ["" {"/" (E :home {:public true
                       :view `views/home
                       })
         "/ws" (E :websocket {:public true
                              :GET 'sparkboard.server.core/ws-handler})
         "/upload" (E :asset/upload {:POST 'sparkboard.assets/upload-handler})
         ["/assets/" [bidi/uuid :asset/id]] (E :asset/serve {:public true 
                                                             :GET 'sparkboard.assets/serve-asset})
         ["/documents/" :file/name] (E :markdown/file
                                       {:GET 'sparkboard.server.core/serve-markdown
                                        :public true})
         "/slack/" {"invite-offer" (E :slack/invite-offer
                                      {:view `slack.client/invite-offer})
                    "link-complete" (E :slack/link-complete
                                       {:view `slack.client/link-complete})}
         "/login" (E :account/sign-in {:view `views/account:sign-in
                                       :header? false
                                       :POST 'sparkboard.server.accounts/login!
                                       :public true})
         "/logout" (E :account/logout {:GET 'sparkboard.server.accounts/logout
                                       :public true})
         "/locale/set" (E :account/set-locale {:POST 'sparkboard.i18n/set-locale!})
         "/oauth2" {"/google" {"/launch" (E :oauth2.google/launch {})
                               "/callback" (E :oauth2.google/callback {})
                               "/landing" (E :oauth2.google/landing
                                             {:public true
                                              :GET 'sparkboard.server.accounts/google-landing})}}

         "/domain-availability" (E :domain/availability
                                   {:GET `domain/availability})
         ["/a/" [bidi/uuid :account]]  (E :account/read 
                                          {:query `account/read-query 
                                           :view `account/read-view})
         "/o" {"/index" (E :org/index
                           {:query `org/index:query
                            :view `org/index:view})
               "/new" (E :org/new
                         {:view `org/new:view
                          :POST `org/new!})
               ["/" [bidi/uuid :org]] {"" (E :org/read
                                             {:query `org/read:query
                                              :view `org/read:view})
                                       "/settings" (E :org/settings
                                                      {:view `org/settings-view
                                                       :query `org/settings:query
                                                       :POST `org/settings!})
                                       "/delete" (E :org/delete
                                                    {:POST `org/delete!})
                                       "/new-board" (E :org/new-board
                                                       {:view `board/new:view
                                                        :POST `board/new!})
                                       "/search" (E :org/search
                                                    {:query `org/search:query})}}
         ["/b/" [bidi/uuid :board]] {"" (E :board/read
                                           {:query `board/read:query
                                            :view `board/read:view})
                                     "/new-project" (E :project/new
                                                       {:view `project/new:view
                                                        :POST `project/new!})
                                     "/register" (E :board/register
                                                    {:view `board/register:view
                                                     :POST `board/register!})}
         ["/p/" [bidi/uuid :project]] {"" (E :project/read
                                             {:query `project/read:query
                                              :view `project/read:view})}
         ["/m/" [bidi/uuid :member]] {"" (E :member/read
                                            {:query `member/read:query
                                             :view `member/read:view})}}]))

(defn path-for
  "Given a route vector like `[:route/id {:param1 val1}]`, returns the path (string)"
  [route & {:as options}]
  (let [routes ()])
  (cond-> (cond (string? route) route
                (keyword? route) (bidi/path-for @!routes route options)
                (vector? route) (apply bidi/path-for @!routes route)
                :else (bidi/path-for @!routes route options))
          (:query-params options)
          (-> (query-params/merge-query (:query-params options)) :path)))

(defn entity [{:as e :entity/keys [kind id]} key]
  (when e

    (let [tag (keyword (name kind) (name key))]
      (path-for tag kind id))))


(defn match-path
  "Resolves a path (string or route vector) to its handler map (containing :view, :query, etc.)"
  [path]
  (impl/match-route @!routes (path-for path)))

(comment
  (sparkboard.impl.routes/resolve-endpoint
    {:query `org/index:query
     :view `org/index:view
     })
  (match-path "/o/index"))

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
   (defn POST [route body]
     (-> (js/fetch (path-for route)
                   (if (instance? js/FormData body)
                     (j/lit {:method "POST"
                             :headers {"Accept" "application/transit+json"}
                             :body body})
                     (j/lit {:headers {"Accept" "application/transit+json"
                                       "Content-type" "application/transit+json"}
                             :body (t/write body)
                             :method "POST"})))
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