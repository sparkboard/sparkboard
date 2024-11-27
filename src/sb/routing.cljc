(ns sb.routing
  (:refer-clojure :exclude [resolve])
  (:require #?(:cljs ["react" :as react])
            #?(:cljs [vendor.pushy.core :as pushy])
            #?(:clj [cljs.analyzer.api :as ana])
            [applied-science.js-interop :as j]
            [clojure.pprint]
            [clojure.string :as str]
            [re-db.reactive :as r]
            [re-db.xform :as xf]
            [reitit.core :as reit]
            [sb.client.local-storage :as local]
            [sb.http :as http]
            [sb.query-params :as query-params]
            [sb.schema :as sch]
            [sb.transit :as t]
            [sb.util :as u]
            [shadow.resource])
  #?(:clj (:import [java.util UUID]))
  #?(:cljs (:require-macros [sb.routing])))
;; TODO
;; - when creating paths, first look up the router for the tag
;; - create a tag index - routes are all {"route" {:method {endpoint} :name :some/tag}}

;; In this namespace we're dealing with three representations
;; 1. paths: "/b/123-abc"
;; 2. tag & params: [sb.app.board.ui/show {:board-id #uuid "123-abc"}]
;; 3. matches:
;; {:router/root {:match/endpoints {:view {:endpoint/sym sb.app.board.ui/show
;;                                        :endpoint/tag sb.app.board.ui/show
;;                                        :endpoint/method :view
;;                                        :endpoint/path "/b/:board-id"}}
;;               :match/data {:endpoints {:view {:endpoint/sym sb.app.board.ui/show
;;                                               :endpoint/tag sb.app.board.ui/show
;;                                               :endpoint/method :view
;;                                               :endpoint/path "/b/:board-id"}}
;;                            :name sb.app.board.ui/show}
;;               :match/path "/b/123-abc"
;;               :match/params {:board-id [:entity/id #uuid "123-abc"]
;;                              :query-params nil}}}
;;
;; paths and matches' can also represent a modal together with a root:
;; "/b/123-abc(modal:m/456-abc)" {:router/root ... :router/modal ...}
;;
;; to get a path from the combination of a root and modal tag&params `update-matches` can be chained with itself and `aux:emit-matches`

(defonce !endpoints (r/atom []))

(r/redef !routes (r/reaction (->> @!endpoints
                                  (filter :endpoint/path)
                                  (reduce (fn assoc-endpoint
                                            [m {:as endpoint :keys [endpoint/path]}]
                                            (-> m
                                                (assoc-in [path :endpoints (:endpoint/method endpoint)] endpoint)
                                                (update-in [path :name] #(or % (:endpoint/tag endpoint)))))
                                          {}))))

(r/redef !router (xf/transform !routes
                               (map reit/router)))

(r/redef !tag->endpoints (r/reaction
                           (reduce (fn [index {:as endpoint :endpoint/keys [tag method]}]
                                     (assoc-in index [tag method] endpoint))
                                   {}
                                   @!endpoints)))

(r/redef !tag->router (r/reaction
                        (into {}
                              (keep (fn [[tag data]]
                                      (when-let [router (-> data :view :view/router)]
                                        [tag router])))
                              @!tag->endpoints)))

(r/redef !aliases (r/reaction
                    (into {}
                          (mapcat (fn [[_ {:as data :keys [name endpoints]}]]
                                    (for [{:keys [endpoint/tag]} (vals endpoints)
                                          :when (not= tag name)]
                                      [tag name])))
                          (reit/routes @!router))))
(comment
  @!routes
  @!router
  (reit/routes @!router)
  @!tag->data
  @!tag->endpoints
  @!tag->router
  @!aliases)

(defonce !views (atom {}))
(defonce !location (r/atom nil))
(defn tag->endpoint [tag method]
  (get-in @!tag->endpoints [tag method]))

(defonce !recent-ids (local/$local-storage ::recently-viewed-ids ()))

(r/redef !track-recents
  (r/reaction!
    (swap! !recent-ids
           (fn [ids]
             (->> (concat (->> @!location vals
                               (mapcat :match/params)
                               (filter (comp #{:org-id :board-id :project-id :note-id :membership-id :this-account-id} key))
                               (map (comp sch/unwrap-id val)))
                          ids)
                  distinct
                  (take 6))))))

(defonce !login-redirect (local/$local-storage ::login-redirect nil))

(add-watch !location :login
           (fn [_ _ old new]
             (when (= 'sb.app.account.ui/sign-in
                      (-> new :router/root :match/endpoints :view :endpoint/sym))
               (let [{{:match/keys [endpoints params]} :router/root} old]
                 (reset! !login-redirect [(-> endpoints :view :endpoint/sym) params])))))

(defn aux:parse-path
  "Given a `path` string, returns map of {<route-name>, <path>}

  :root will contain the base route.

  Uses the Angular auxiliary route format of http://root-path(name:path//name:path).

  Ignores query parameters."
  [path]
  (let [[_ root-string auxiliary-string query-string] (re-find #"([^(?]*)(?:\(([^(]+)\))?(\?.*)?" path)]
    (merge {:router/root  (u/ensure-prefix root-string "/")
            :query-string query-string}
           (->> (when auxiliary-string
                  (str/split auxiliary-string #"//"))
                (reduce (fn [m path]
                          (let [[_ router path] (re-find #"([^:]+)(?::?(.*))" path)]
                            (assoc m (keyword "router" router)
                                     (u/ensure-prefix path "/")))) {})))))

(defn aux:emit-matches
  "Given a map of the form {<route-name>, <path>},
   emits a list of auxiliary routes, wrapped in parentheses.

   :root should contain the base route, if any.

   e.g. /hello(nav:details/edit//drawer:profile/photo)"
  [matches]
  (str

    ;; root route
    (u/ensure-prefix (-> matches :router/root :match/path) "/")

    ;; aux routes
    (some->> (dissoc matches :router/root)
             (keep (fn [[router {:keys [match/path]}]]
                     (assert (string? path))
                     (when path
                       (str (name router)
                            (some-> path
                                    (u/trim-prefix "/")
                                    u/some-str
                                    (->> (str ":")))))))
             (seq)
             (str/join "//")
             (u/wrap "()"))
    (query-params/query-string (get-in matches [:router/root :match/params :query-params]))))

(defn parse-match [{:as match :keys [data path-params query-params path]}]
  (when (and match (not (reit/partial-match? match)))
    {:match/endpoints (:endpoints data)
     :match/data      data
     :match/path      path
     :match/params    (reduce-kv (fn [out k v]
                                   (if (str/ends-with? (name k) "-id")
                                     (assoc out k [:entity/id #?(:cljs (uuid v)
                                                                 :clj  (UUID/fromString v))])
                                     out))
                                 (assoc path-params :query-params query-params)
                                 path-params)}))

(defn aux:match-by-path [path]
  (let [{:as router-paths :keys [query-string]} (aux:parse-path path)
        matches (cond-> (into {}
                              (map (fn [[router path]]
                                     [router (parse-match (reit/match-by-path @!router (u/ensure-prefix path "/")))]))
                              (dissoc router-paths :query-string))
                        query-string
                        (assoc-in [:router/root :match/params :query-params] (query-params/path->map query-string)))]
    (when (-> matches :router/root :match/data)
      matches)))

(comment
  (aux:match-by-path "/oauth2/google/launch")
  (aux:match-by-path "/"))

(defn match-by-tag [tag params]
  (when tag
    (let [tag    (@!aliases tag tag)
          {:keys [query-params]} params
          params (as-> params params
                       (dissoc params :query-params)
                       (reduce-kv (fn [params k v]
                                    (if (str/ends-with? (name k) "-id")
                                      (assoc params k (sch/unwrap-id v))
                                      params))
                                  params
                                  params))]
      (cond-> (parse-match (reit/match-by-name @!router tag params))
              query-params
              (-> (assoc-in [:match/params :query-params] query-params)
                  (update :match/path str (query-params/query-string query-params)))))))

(def path-by-name (comp :match/path match-by-tag))

(comment
  (match-by-tag 'sb.app.asset.data/upload! {:query-params {:a 1}})
  (path-by-name 'sb.app.asset.data/upload! {:query-params {:a 1}})
  (aux:match-by-path "/upload?a=1")
  (aux:parse-path "/upload?a=1"))

(defn select-keys-by [m selector]
  (reduce-kv (fn [out k v]
               (if (and (keyword? k) (selector k))
                 (assoc out k v)
                 out))
             {}
             m))

(defn endpoint-maps [sym meta]
  (let [endpoint {:endpoint/sym sym
                  :endpoint/tag (or (:endpoint/tag meta) sym)}
        methods  (:endpoint meta)]
    (concat (for [method [:get
                          :post
                          :view]
                  :let [route (get methods method)]
                  :when route]
              (cond-> (assoc endpoint
                        :endpoint/method method
                        :endpoint/path route)
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
           (comp (filter #(str/starts-with? (name (ns-name %)) "sb."))
                 (mapcat (comp vals ns-publics))
                 (filter (comp :endpoint meta))
                 (mapcat #(endpoint-maps (symbol %) (meta %))))
           (all-ns))))

#?(:clj
   (defmacro register-route [name {:as opts :keys [alias-of route]}]
     (let [sym-meta (cond-> opts
                            alias-of
                            (merge (-> (if (:ns &env)
                                         (:meta (ana/resolve &env alias-of))
                                         (meta (clojure.core/resolve alias-of)))
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
          (swap! sb.routing/!views assoc '~(symbol (str *ns*) (str name)) (with-meta ~(or alias-of name) ~(u/select-by sym-meta (comp #{"view"} namespace))))))))

(defonce !history (atom nil))

(comment
  (reit/match-by-name @!router 'sb.app.asset.data/upload! {})
  (reit/match-by-name @!router 'sb.app.board.data/show {:board-id (random-uuid)})
  (match-by-tag 'sb.app.asset-data/upload! {}))

(defn update-matches
  "Given a matches map and a route like `:route/id {:param1 val1}`,
  returns either a new matches map for a `:router/root` match or
  updates the matches map with a new modal match"
  [location tag & {:as params}]
  (let [match  (if (map? tag)
                 tag ;; TODO is this branch ever taken? should it?
                 (match-by-tag tag (dissoc params :query-params)))
        router (or (get @!tag->router tag) :router/root)]
    (if (= router :router/root)
      {router match}
      (assoc location router match))))

(defn path-for
  "Given a route vector like `[:route/id {:param1 val1}]`, returns the path (string)"
  [tag & {:as params}]
  (if (vector? tag)
    (apply path-for tag)
    (aux:emit-matches (update-matches @!location tag params))))

(defn resolve [tag & args]
  (cond (string? tag) (aux:match-by-path tag)
        (vector? tag) (apply resolve tag)
        (symbol? tag) (when-let [path (apply path-for tag args)]
                        (aux:match-by-path path))))

(comment
  (time (resolve "/b/a1eebd1e-8b71-4925-bbfd-1b7f6a6b680e?a=1"))
  (time (path-for ['sb.app.board.ui/show {:board-id     [:entity/id #uuid "a1eebd1e-8b71-4925-bbfd-1b7f6a6b680e"]
                                          :query-params {:foo "blah"}}]))
  (time (aux:match-by-path "/")))

(defn init-endpoints! [endpoints]
  (reset! !endpoints endpoints)
  #?(:cljs
     (do (reset! !history (pushy/pushy (partial reset! !location) aux:match-by-path))
         (pushy/start! @!history))))

(defn entity-route [{:as e :entity/keys [kind id]} key]
  (when (and kind id)
    [(symbol (str "sb.app." (name kind) "." key))
     {(keyword (str (#({"account" "this-account"} % %) (name kind)) "-id")) id}]))

(defn entity-path [e key]
  (some->> (entity-route e key)
           (apply path-for)))



#?(:cljs
   (do

     (defn merge-query! [query-params]
       (pushy/set-token! @!history (aux:emit-matches (update-in @!location [:router/root :match/params :query-params] merge query-params))))

     (defn clear-router! [router]
       (pushy/set-token! @!history (path-for (-> @!location
                                                 (dissoc router)
                                                 (update-vals :match/path)))))

     (defn dissoc-router! [router]
       (pushy/set-token! @!history (aux:emit-matches (dissoc @!location router))))

     (defn nav!* [matches]
       (let [path (aux:emit-matches matches)]
         (if (-> (or (:router/modal matches)
                     (:router/root matches))
                 :match/endpoints
                 :view)
           (js/setTimeout #(pushy/set-token! @!history path) 0)
           (j/assoc-in! js/window [:location :href] path))))

     (defn nav! [tag & {:as params}]
       (if (vector? tag)
         (apply nav! tag)
         (nav!* (update-matches @!location tag params))))))

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

(defn normalize-slashes [path]
  ;; remove trailing /'s, but ensure path starts with /
  (-> path
      (cond-> (and (> (count path) 1) (str/ends-with? path "/"))
              (subs 0 (dec (count path))))
      (u/ensure-prefix "/")))

(comment
  @!tags)

(comment
  ((name-index routers) :org)
  (aux:match-by-path (str "/o/" (random-uuid)))
  (aux:match-by-path p)
  (path-by-name router :foo {:org-id (random-uuid)})
  (path-for `assets/upload!)
  )

