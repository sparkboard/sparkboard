(ns sb.query-params
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str])
  #?(:clj (:import [java.net URL URI])))

#?(:cljs
   (defn params->map
     "Returns query map from URL.searchParams"
     [search-params]
     (->> search-params
          (reduce (fn [m [k v]]
                    (cond-> m
                            (not (str/blank? v))
                            (assoc (keyword k) v))) {}))))

(defn path->map
  "Returns query map from url"
  [url]
  (not-empty
   #?(:cljs
      (-> (js/URL. url "https://example.com")
          (j/get :searchParams)
          (params->map))
      :clj
      (when-let [query (:query (bean (URI. url)))]
        (->> (str/split query #"&")
             (map #(str/split % #"="))
             (keep (fn [[k v]] (when-not (str/blank? v)
                                 [(keyword k) v])))
             (into {}))))))

(defn url-encode [x] 
  #?(:cljs (js/encodeURIComponent x)
     :clj  (-> (str x)
               (java.net.URLEncoder/encode "UTF-8")
               (str/replace "+" "%20"))))

(defn query-string
  "Returns query string from map, including '?'. Removes empty values. Returns nil if empty."
  [m]
  (some->> m
           (reduce (fn [out [k v]]
                        (if-let [v (cond (string? v) (when-not (str/blank? v) v)
                                         (nil? v) nil
                                         (coll? v) (not-empty v)
                                         :else (str v))]
                          (conj out (str (url-encode (name k))
                                         "="
                                         (url-encode v)))
                          out)) 
                   [])
           seq
           (str/join "&")
           (str "?")))

(defn merge-query
  "Returns map of updated path and query map"
  [path params]
  #?(:cljs
     (j/let [^js {:keys [pathname hash searchParams]} (js/URL. path "https://example.com")
             new-query-map (merge (params->map searchParams)
                                  params)
             new-path (str pathname
                           (query-string new-query-map)
                           hash)]
       {:query-params new-query-map
        :path new-path})
     :clj
     (let [new-query-map (merge (path->map path) params)
           new-path (str (str/replace path #"\?.*$" "")
                         (query-string new-query-map))]
       {:query-params new-query-map
        :path new-path})))

(comment
 (path->map "/whatever?x=1")
 (-> (merge-query "/whatever?x=1" {:x "2/3"})
     :path
     path->map
     ))

