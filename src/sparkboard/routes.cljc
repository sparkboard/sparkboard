(ns sparkboard.routes
  (:refer-clojure :exclude [uuid])
  (:require #?(:cljs ["react" :as react])
            #?(:cljs [shadow.lazy :as lazy])
            #?(:cljs [vendor.pushy.core :as pushy])
            #?(:cljs [yawn.view :as v])
            #?(:cljs [yawn.hooks :as hooks])
            [applied-science.js-interop :as j]
            [bidi.bidi :as bidi :refer [uuid]]
            [sparkboard.transit :as t]
            [re-db.api :as db]
            [sparkboard.client.slack :as-alias slack.client]
            [sparkboard.query-params :as query-params]
            [sparkboard.entities.board :as-alias board]
            [sparkboard.entities.domain :as-alias domain]
            [sparkboard.entities.member :as-alias member]
            [sparkboard.entities.org :as-alias org]
            [sparkboard.entities.project :as-alias project]
            [sparkboard.entities.account :as-alias account]
            [sparkboard.http :as http]
            [sparkboard.util :as u]
            [sparkboard.impl.routes :as impl :refer [E wrap-endpoints]])
  #?(:cljs (:require-macros [sparkboard.routes])))

(def ENTITY-ID [uuid :entity/id])

(def routes
  "Route definitions.
  :view     - symbol pointing to a (client) view for the single-page app
  :query    - symbol pointing to a (server) function providing data for the route
  :POST     - symbol pointing to a (server) function accepting [req, params & body]
  :GET      - symbol pointing to a (server) function accepting a request map"
  (wrap-endpoints
    ["" {"/"                           {:id   :home
                                        :public true
                                        :VIEW `account/home}
         "/ws"                         {:id  :websocket
                                        :GET 'sparkboard.server.core/ws-handler}
         "/upload"                     {:id   :asset/upload
                                        :POST 'sparkboard.assets/upload-handler}
         ["/assets/" [uuid :asset/id]] {:id  :asset/serve
                                        :GET 'sparkboard.assets/serve-asset}
         ["/documents/" :file/name]    {:id  :markdown/file
                                        :GET 'sparkboard.server.core/serve-markdown}
         "/slack/"                     {"invite-offer"  {:id   :slack/invite-offer
                                                         :VIEW `slack.client/invite-offer}
                                        "link-complete" {:id   :slack/link-complete
                                                         :VIEW `slack.client/link-complete}}
         "/login"                      {:id   :account/sign-in
                                        :public true
                                        :VIEW `account/account:sign-in
                                        :POST 'sparkboard.server.accounts/login!}
         "/logout"                     {:id  :account/logout
                                        :GET 'sparkboard.server.accounts/logout}
         "/locale/set"                 {:id   :account/set-locale
                                        :POST 'sparkboard.i18n/set-locale!}
         "/oauth2"                     {"/google" {"/launch"   {:id :oauth2.google/launch}
                                                   "/callback" {:id :oauth2.google/callback}
                                                   "/landing"  {:id  :oauth2.google/landing
                                                                :GET 'sparkboard.server.accounts/google-landing}}}

         "/domain-availability"        {:id  :domain/availability
                                        :GET `domain/availability}
         "/orgs"                       {:id    :account/orgs
                                        :QUERY `account/orgs-query}
         "/account"                    {:id    :account/read
                                        :VIEW  `account/read
                                        :QUERY `account/read-query}
         "/o"                          {"/new"               {:id   :org/new
                                                              :VIEW `org/new
                                                              :POST `org/new!}
                                        ["/" [uuid :org-id]] {""
                                                              {:id    :org/read
                                                               :VIEW  `org/read
                                                               :QUERY `org/read-query}
                                                              "/settings"
                                                              {:id    :org/edit
                                                               :VIEW  `org/edit
                                                               :QUERY `org/edit-query
                                                               :POST  `org/edit!}
                                                              "/delete"
                                                              {:id   :org/delete
                                                               :POST `org/delete!}
                                                              "/search"
                                                              {:id    :org/search
                                                               :QUERY `org/search-query}}}
         "/b"                          {"/new"
                                        {:id   :board/new
                                         :VIEW `board/new
                                         :POST `board/new!}
                                        ["/" [uuid :board-id]] {""
                                                                {:id    :board/read
                                                                 :QUERY `board/read-query
                                                                 :VIEW  `board/read}
                                                                "/settings"
                                                                {:id    :board/edit
                                                                 :VIEW  `board/edit
                                                                 :QUERY `board/edit-query
                                                                 :POST  `board/edit!}
                                                                "/register"
                                                                {:id   :board/register
                                                                 :VIEW `board/register
                                                                 :POST `board/register!}}}
         ["/p"]                        {"/new"
                                        {:id   :project/new
                                         :VIEW `project/new
                                         :POST `project/new!}
                                        ["/" [uuid :project-id]]
                                        {:id    :project/read
                                         :VIEW  `project/read
                                         :QUERY `project/read-query}}
         ["/m/" [uuid :member-id]]     {""
                                        {:id    :member/read
                                         :VIEW  `member/read
                                         :QUERY `member/read-query}}}]))
(defn path-for
  "Given a route vector like `[:route/id {:param1 val1}]`, returns the path (string)"
  [route & {:as options}]
  (cond-> (cond (string? route) route
                (keyword? route) (bidi/path-for routes route options)
                (vector? route) (apply bidi/path-for routes route)
                :else (bidi/path-for routes route options))
          (:query-params options)
          (-> (query-params/merge-query (:query-params options)) :path)))

(def match-path
  "Resolves a path (string or route vector) to its handler map (containing :view, :query, etc.)"
  ;; memoize
  (fn [path]
    (impl/match-route routes (path-for path))))

(defn entity [{:as e :entity/keys [kind id]} key]
  (when e
    (let [tag (keyword (name kind) (name key))]
      (path-for tag (keyword (str (name kind) "-id")) id))))

(comment
  (sparkboard.impl.routes/resolve-endpoint
    {:QUERY `org/list-query
     :VIEW  `org/list-view
     })
  (match-path "/o/index"))

#?(:cljs
   (do

     (defn set-location! [location]
       (let [modal    (-> location :params :query-params :modal)
             location (cond-> location modal (assoc :modal (impl/match-route routes modal)))]
         (db/transact! [[:db/retractEntity :env/location]
                        (assoc location :db/id :env/location)])))

     (defonce history (pushy/pushy set-location! (partial impl/match-route routes)))

     (defn merge-query [params]
       (:path (query-params/merge-query (:path (db/get :env/location :params)) params)))

     (defn merge-query! [params]
       (pushy/set-token! history (merge-query params)))

     (defn href [route & args]
       (let [path  (apply path-for route args)
             match (match-path path)]
         (if (= :modal (:target (meta (:VIEW match))))
           (merge-query {:modal path})
           path)))

     (defn set-modal! [route]
       (merge-query! {:modal (some-> route path-for)}))

     (defn close-modal! [] (merge-query! {:modal nil}))

     (defn set-path! [& args]
       (js/setTimeout #(pushy/set-token! history (apply href args)) 0))))

(defn breadcrumb [path] (impl/breadcrumb routes path))

(defn path->route [path]
  (cond-> path
          (string? path)
          (->> (impl/match-route routes) :route)))

#?(:cljs
   (defn POST [route body]
     (let [path (path-for route)]
       (tap> [:POST path body])
       (-> (js/fetch path
                     (if (instance? js/FormData body)
                       (j/lit {:method  "POST"
                               :headers {"Accept" "application/transit+json"}
                               :body    body})
                       (j/lit {:headers {"Accept"       "application/transit+json"
                                         "Content-type" "application/transit+json"}
                               :body    (t/write body)
                               :method  "POST"})))
           (.then http/format-response)))))

#?(:cljs
   (defn GET [route & args]

     (-> (apply path-for route args)
         (js/fetch (j/lit {:headers {"Accept"       "application/transit+json"
                                     "Content-type" "application/transit+json"}
                           :method  "GET"}))
         (.then http/format-response))))

(defn tag [tag endpoint]
  (bidi/tag (delay endpoint) tag))