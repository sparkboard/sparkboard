(ns sparkboard.routes
  (:refer-clojure :exclude [resolve])
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
            [shadow.resource]
            [clojure.walk :as walk]
            [sparkboard.util :as u])
  #?(:cljs (:require-macros [sparkboard.routes])))

(defn support-entity-ids!
  "UUIDs in routes are wrapped as [:entity/id ...] lookup refs."
  []
  (let [wrapped-uuid (fn [s]
                       [:entity/id (bidi/uuid s)])]
    (extend-protocol bidi/PatternSegment
      #?(:clj  clojure.lang.Symbol
         :cljs Symbol)
      (segment-regex-group [this]
        (if (= this 'entity/id)
          "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
          (throw (ex-info (str "Unidentified function qualifier to pattern segment: " this) {}))))
      (transform-param [this]
        (if (= this 'entity/id)
          wrapped-uuid
          (throw (ex-info (str "Unrecognized function " this) {}))))
      (matches? [this s]
        (and (= this 'entity/id)
             (let [s (if (vector? s) (second s) s)]
               (instance? #?(:clj java.util.UUID :cljs cljs.core.UUID) s))))))

  (extend-protocol bidi/ParameterEncoding
    #?(:clj  clojure.lang.PersistentVector
       :cljs PersistentVector)
    (encode-parameter [s]
      (if (= :entity/id (first s))
        (str (second s))
        (throw (ex-info (str "Invalid URL parameter" s) {}))))))

(support-entity-ids!)

(comment

  [
   (bidi/match-route ["" {["/o/" ['entity/id :org/id]] :foo}]
                     (str "/o/" (random-uuid)))

   (bidi/path-for ["" {["/o/" ['entity/id :org/id]] :foo}]
                  :foo :org/id
                  [:entity/id (random-uuid)])]
  )

(defn normalize-slashes [path]
  ;; remove trailing /'s, but ensure path starts with /
  (-> path
      (cond-> (and (> (count path) 1) (str/ends-with? path "/"))
              (subs 0 (dec (count path))))
      (u/ensure-prefix "/")))

(defn match-path* [routes path]
  (when-let [path (some-> path normalize-slashes)]
    (let [{:as m :keys [route-params endpoints]} (bidi/match-route routes path)
          params (-> (or route-params {})
                     (u/assoc-some :query-params (not-empty (query-params/path->map path))))]
      (if m
        {:match/endpoints endpoints
         :match/path      path
         :match/params    params}
        (prn :no-match! path)))))

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
(defonce !views (atom {}))
(defonce !tags (atom {}))
(defn by-tag
  ([id] (get @!tags id))
  ([id method] (get-in @!tags [id method])))

(defn breadcrumb
  ([path] (breadcrumb @!routes path))
  ([routes path]
   (->> (iteration #(second (re-find #"(.*)/.*$" (or % "")))
                   :initk path)
        (keep (comp :route (partial match-path* routes))))))

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
                       (= 'entity/id (first k))))
            [k]
            k)]
    (if ks
      (assoc m k (assoc-in-route (get m k) ks v))
      (update m k merge-or-return-endpoint v))))

(comment
  (let [r (-> {}
              (assoc-in-route ["/o/" ['entity/id :org-id] "/settings"] {:endpoint/method :view
                                                                        :endpoint/tag    'foo.settings})
              (assoc-in-route ["/o/" ['entity/id :org-id]] {:endpoint/method :view
                                                            :endpoint/tag    'foo.view})
              (assoc-in-route ["/o/" ['entity/id :org-id]] {:endpoint/method :read
                                                            :endpoint/tag    'foo.read})
              )]
    [(bidi/path-for ["" r] 'foo.settings {:org-id (random-uuid)})
     (bidi/path-for ["" r] 'foo.view {:org-id (random-uuid)})])

  )

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
  ;; - :effect true
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
    (concat (for [method [:get
                          :post
                          :view]
                  :let [route (get methods method)]
                  :when route
                  :let [route (cond-> route (string? route) vector)]]
              (cond-> (assoc endpoint
                        :endpoint/method method
                        :endpoint/route route)
                      (= method :view)
                      (merge (select-keys-by meta #(= "view" (namespace %))))))
            (for [method [:query :effect]
                  :when (get methods method)]
              (assoc endpoint
                :endpoint/method method)))))

#?(:clj
   (defn endpoints []
     ;; https://github.com/thheller/shadow-experiments/blob/f5617079ad6fe553b612505047b258036cf85eb8/src/main/shadow/experiments/archetype/website.clj#L19
     (into []
           (comp (filter #(str/starts-with? (name (ns-name %)) "sparkboard."))
                 (mapcat (comp vals ns-publics))
                 (filter (comp :endpoint meta))
                 (mapcat #(endpoint-maps (symbol %) (meta %))))
           (all-ns))))

(comment
  (->> (endpoints)
       (filter (comp #{:view} :endpoint/method))))

#?(:clj (defn meta->sym [m]
          (symbol (str (:ns m)) (str (:name m)))))

#?(:clj
   (defmacro register-route [name {:as opts :keys [alias-of route]}]
     (let [sym-meta (cond-> opts
                            alias-of
                            (merge (-> (if (:ns &env)
                                         (:meta (ana/resolve &env alias-of))
                                         (meta (resolve alias-of)))
                                       (u/select-by (comp #{"view"} namespace)))
                                   {:endpoint {:view route}}))]
       `(do
          ~(if alias-of
             `(def ~(with-meta name sym-meta))
             (do (swap! (ana/current-state) assoc-in [:cljs.analyzer/namespaces
                                                      (symbol (str *ns*))
                                                      :defs
                                                      name
                                                      :meta
                                                      :endpoint
                                                      :view] route)
                 nil))
          (swap! sparkboard.routes/!views assoc '~(symbol (str *ns*) (str name)) (with-meta ~(or alias-of name) ~(u/select-by sym-meta (comp #{"view"} namespace))))))))

#?(:cljs (do
           (defonce !history (atom nil))

           (def set-location!
             (fn [location]
               (let [location (if-let [modal (-> location :match/params :query-params :modal)]
                                (assoc location :modal (match-path* @!routes modal))
                                location)]
                 (db/transact! [[:db/retractEntity :env/location]
                                (assoc location :db/id :env/location)]))))))

(defn endpoints->routes [endpoints]
  (->> endpoints
       (filter :endpoint/route)
       (reduce (fn [routes endpoint]
                 (assoc-in-route routes (:endpoint/route endpoint) endpoint))
               {})))

(defn endpoints->tags [endpoints]
  (->> endpoints
       (reduce (fn [out endpoint]
                 (assoc-in out
                           [(:endpoint/tag endpoint) (:endpoint/method endpoint)]
                           endpoint))
               {})))


(defn path-for
  "Given a route vector like `[:route/id {:param1 val1}]`, returns the path (string)"
  [route & {:as options}]
  {:pre [(symbol? route)]}
  (if (vector? route)
    (apply path-for route)
    (cond-> (bidi/path-for @!routes route options)
            (:query-params options)
            (-> (query-params/merge-query (:query-params options)) :path))))


(defn merge-query [params]
  (:path (query-params/merge-query
           (db/get :env/location :match/path)
           params)))

(defn match-path
  "Resolves a path (string or route vector) to its handler map (containing :view, :query, etc.)"
  [path]
  {:pre [(string? path)]}
  (match-path* @!routes path))

(defn resolve [route & args]
  (cond (string? route) (match-path route)
        (vector? route) (apply resolve route)
        (symbol? route) (match-path (apply path-for route args))))

(defn href [match]
  (if (map? match)
    (let [{:match/keys [path endpoints]} match]
      (if (= :modal (-> endpoints :view :view/target))
        (merge-query {:modal path})
        path))
    (href (resolve match))))

(comment
  (resolve "/b/a1eebd1e-8b71-4925-bbfd-1b7f6a6b680e?a=1")
  (href "/b/a1eebd1e-8b71-4925-bbfd-1b7f6a6b680e?a=1")
  (href ['sparkboard.app.board/show {:board-id [:entity/id #uuid "a1eebd1e-8b71-4925-bbfd-1b7f6a6b680e"]
                                     :query-params {:foo "blah"}}])
  (def p "/b/a1eebd1e-8b71-4925-bbfd-1b7f6a6b680e")
  (match-path p)
  (def r ['sparkboard.app.board/show {:board-id [:entity/id #uuid "a1eebd1e-8b71-4925-bbfd-1b7f6a6b680e"]
                                      :foo "blah"}])


  (time (resolve r))
  (time (resolve p))

  @!routes
  (by-tag 'sparkboard.app.board/show)
  (href ['sparkboard.app.board/show {:board-id (random-uuid)}])
  (path-for 'sparkboard.app.org/show {:org-id (random-uuid)})
  (match-path "/"))

(defn init-endpoints! [endpoints]
  (reset! !tags (endpoints->tags endpoints))
  (reset! !routes ["" (endpoints->routes endpoints)])
  #?(:cljs
     (do (reset! !history (pushy/pushy set-location! match-path))
         (pushy/start! @!history))))

(def ENTITY-ID [bidi/uuid :entity/id])

(defn entity [{:as e :entity/keys [kind id]} key]
  (when e
    (let [tag    (symbol (str "sparkboard.app." (name kind)) (name key))
          params {(keyword (str (name kind) "-id")) id}]
      (href [tag params]))))

(defn entity-route [{:as e :entity/keys [kind id]} key]
  (when e
    [(symbol (str "sparkboard.app." (name kind)) (name key))
     {(keyword (str (name kind) "-id")) id}]))

#?(:cljs
   (do

     (defn merge-query! [params]
       (pushy/set-token! @!history (merge-query params)))

     (defn set-modal! [route]
       (merge-query! {:modal (some-> route path-for)}))

     (defn close-modal! [] (merge-query! {:modal nil}))

     (defn set-path! [route & args]
       (let [{:as match :match/keys [path endpoints]} (apply resolve route args)]
         (if (:view endpoints)
           (js/setTimeout #(pushy/set-token! @!history (href match)) 0)
           (j/assoc-in! js/window [:location :href] path))))))

(defn path->route [path]
  (cond-> path
          (string? path)
          (->> (match-path* @!routes) :route)))

#?(:cljs
   (defn POST [route body]
     (let [path (path-for route)]
       (-> (js/fetch path
                     (if (instance? js/FormData body)
                       (j/lit {:method  "POST"
                               :headers {"Accept" "application/transit+json"}
                               :body    body})
                       (j/lit {:method  "POST"
                               :headers {"Accept"       "application/transit+json"
                                         "Content-type" "application/transit+json"}
                               :body    (t/write body)})))
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

(comment
  @!tags)