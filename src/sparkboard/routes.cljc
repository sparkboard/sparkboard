(ns sparkboard.routes
  (:require #?(:cljs [vendor.pushy.core :as pushy])
            #?(:cljs ["react" :as react])
            #?(:clj [cljs.analyzer.api :as ana])
            [bidi.bidi :as bidi]
            [applied-science.js-interop :as j]
            [clojure.string :as str]
            [sparkboard.transit :as t]
            [re-db.api :as db]
            [sparkboard.query-params :as query-params]
            [sparkboard.http :as http]
            [sparkboard.impl.routes :as impl]
            [shadow.resource]
            [clojure.walk :as walk]
            [sparkboard.util :as u])
  #?(:cljs (:require-macros [sparkboard.routes])))

(comment
  (shadow.cljs.devtools.api/get-worker :web)
  )

(defrecord EndpointsMatch [tags endpoints]
  bidi/Matched
  (resolve-handler [this m]
    ;; called when matching a path to a route
    (bidi/succeed nil (assoc m :endpoints endpoints)))
  (unresolve-handler [this m]
    (when (contains? tags (:handler m)) ""))

  bidi/RouteSeq
  (gather [this context]
    [(bidi/map->Route (assoc context :endpoints endpoints))]))

(defonce !routes (atom ["" {}]))
(defonce !tags (atom {}))
(defn by-tag [id method] (get-in @!tags [id method]))

(defn merge-or-return-endpoint [acc x]
  (cond (nil? acc) (->EndpointsMatch #{(:endpoint/tag x)}
                                     {(:endpoint/method x) x})
        (instance? EndpointsMatch acc) (let [endpoints (assoc (:endpoints acc)
                                                         (:endpoint/method x)
                                                         x)]
                                         (->EndpointsMatch (into #{} (map :endpoint/tag) (vals endpoints))
                                                           endpoints))
        (map? acc) (update acc "" merge-or-return-endpoint x)))

(defn assoc-in-route
  [m [k & ks] v]
  (let [m (if (instance? EndpointsMatch m)
            {"" m}
            m)
        k (if (or (keyword? k)
                  (and (vector? k)
                       (= bidi/uuid (first k))))
            [k]
            k)]
    (if ks
      (assoc m k (assoc-in-route (get m k) ks v))
      (update m k merge-or-return-endpoint v))))
(comment
  (let [r (-> {}
              (assoc-in-route ["/o/" [bidi/uuid :org-id] "/settings"] {:endpoint/method :view
                                                                       :endpoint/tag    'foo.settings})
              (assoc-in-route ["/o/" [bidi/uuid :org-id]] {:endpoint/method :view
                                                           :endpoint/tag    'foo.view})
              (assoc-in-route ["/o/" [bidi/uuid :org-id]] {:endpoint/method :read
                                                           :endpoint/tag    'foo.read})
              )]
    [(bidi/path-for ["" r] 'foo.settings {:org-id (random-uuid)})
     (bidi/path-for ["" r] 'foo.view {:org-id (random-uuid)})]
    ))

(defn select-keys-by [m selector]
  (reduce-kv (fn [out k v]
               (if (and (keyword? k) (selector k))
                 (assoc out k v)
                 out))
             {}
             m))

(defn endpoint-maps [sym meta]
  ;; endpoint-map may include
  ;; - :query true
  ;; - :fn true
  ;; - :get [..route]
  ;; - :post [..route]
  ;; - :view [..route]

  #_[{:endpoint/method :get
      :endpoint/route  []
      :endpoint/sym    ...
      :endpoint/tag    ...}]
  (let [endpoint {:endpoint/sym sym
                  :endpoint/tag (or (:endpoint/tag meta) sym)}
        methods  (:endpoint meta)]
    (concat (for [method [:get :post :view]
                  :let [route (get methods method)]
                  :when route]
              (cond-> (assoc endpoint
                        :endpoint/method method
                        :endpoint/route (walk/postwalk-replace {''uuid 'uuid} route))
                      (= method :view)
                      (merge (select-keys-by meta #(= "view" (namespace %))))))
            (for [method [:query :fn]
                  :when (get methods method)]
              (assoc endpoint
                :endpoint/method method)))))

#?(:clj
   (defn clj-endpoints []
     ;; https://github.com/thheller/shadow-experiments/blob/f5617079ad6fe553b612505047b258036cf85eb8/src/main/shadow/experiments/archetype/website.clj#L19
     (into []
           (comp (filter #(str/starts-with? (name (ns-name %)) "sparkboard."))
                 (mapcat (comp vals ns-publics))
                 (filter (comp :endpoint meta))
                 (mapcat #(endpoint-maps (symbol %) (meta %))))
           (all-ns))))

(comment
  (clj-endpoints))

#?(:clj
   (defn view-endpoints [cljs-env]
     (into []
           (comp (filter #(str/starts-with? (str (key %)) "sparkboard.views"))
                 (mapcat (comp vals :defs val))
                 (filter (comp :view
                               :endpoint
                               :meta))
                 (mapcat #(endpoint-maps (:name %) (:meta %))))
           (:cljs.analyzer/namespaces cljs-env))))

#?(:cljs (do
           (defonce !history (atom nil))

           (def set-location!
             (fn [location]
               (let [location (if-let [modal (-> location :match/params :query-params :modal)]
                                (assoc location :modal (impl/match-route @!routes modal))
                                location)]
                 (db/transact! [[:db/retractEntity :env/location]
                                (assoc location :db/id :env/location)]))))))

(defn endpoints->routes [endpoints]
  (->> endpoints
       (filter :endpoint/route)
       (reduce (fn [routes endpoint]
                 (assoc-in-route routes
                                 (walk/postwalk-replace {'uuid bidi/uuid}
                                                        (:endpoint/route endpoint))
                                 endpoint))
               {})))

(defn endpoints->tags [endpoints]
  (->> endpoints
       (filter (comp #{:query :fn} :endpoint/method))
       (reduce (fn [out endpoint]
                 (assoc-in out
                           [(:endpoint/tag endpoint) (:endpoint/method endpoint)]
                           endpoint))
               {})))

(defn init-endpoints! [endpoints]
  (reset! !tags (endpoints->tags endpoints))
  (reset! !routes ["" (endpoints->routes endpoints)])
  #?(:cljs
     (do (reset! !history (pushy/pushy set-location! (partial impl/match-route @!routes)))
         (pushy/start! @!history))))

(def ENTITY-ID [bidi/uuid :entity/id])

(defn dequote [id]
  (if (and (list? id) (= 'quote (first id)))
    (second id)
    id))

(defn path-for
  "Given a route vector like `[:route/id {:param1 val1}]`, returns the path (string)"
  [route & {:as options}]
  (cond-> (cond (string? route) route
                (keyword? route) (bidi/path-for @!routes route options)
                (vector? route) (apply bidi/path-for @!routes (update route 0 dequote))
                :else (bidi/path-for @!routes route options))
          (:query-params options)
          (-> (query-params/merge-query (:query-params options)) :path)))

(def match-path
  "Resolves a path (string or route vector) to its handler map (containing :view, :query, etc.)"
  ;; memoize
  (fn [path]
    (impl/match-route @!routes (path-for path))))

(comment
  @!routes
  (path-for 'sparkboard.views.board/read)
  (path-for 'sparkboard.views.org/read {:org-id (random-uuid)})
  (match-path "/"))

(defn entity [{:as e :entity/keys [kind id]} key]
  (when e
    (let [tag    (symbol (str "sparkboard.views." (name kind)) (name key))
          params {(keyword (str (name kind) "-id")) id}
          path   (path-for tag params)]
      path)))

(defn merge-query [params]
  (:path (query-params/merge-query
           (db/get :env/location :match/path)
           params)))

(defn href [route & args]
  (let [path  (apply path-for route args)
        match (match-path path)]
    (if (= :modal (-> match :match/endpoints :view :endpoint/view meta :view/target))
      (merge-query {:modal path})
      path)))

#?(:cljs
   (do

     (defn merge-query! [params]
       (pushy/set-token! @!history (merge-query params)))

     (defn set-modal! [route]
       (merge-query! {:modal (some-> route path-for)}))

     (defn close-modal! [] (merge-query! {:modal nil}))

     (defn set-path! [& args]
       (js/setTimeout #(pushy/set-token! @!history (apply href args)) 0))))

(defn breadcrumb [path] (impl/breadcrumb @!routes path))

(defn path->route [path]
  (cond-> path
          (string? path)
          (->> (impl/match-route @!routes) :route)))

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

#?(:clj
   (defmacro client-endpoints []
     (->> (concat (clj-endpoints)
                  (view-endpoints @(ana/current-state)))
          (mapv #(-> (dissoc % :endpoint/sym)
                     (cond-> (= :view (:endpoint/method %))
                             (assoc :endpoint/view (:endpoint/sym %)))
                     (update :endpoint/tag (fn [sym] `'~sym))
                     (update :endpoint/route
                             (partial walk/postwalk-replace {'uuid  'bidi.bidi/uuid
                                                             ''uuid 'bidi.bidi/uuid})))))))

(comment
  @!tags
  (sparkboard.routes/client-endpoints))