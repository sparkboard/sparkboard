(ns sparkboard.assets
  (:require [clojure.java.io :as io]
            [sparkboard.server.env :as env]
            [amazonica.aws.s3 :as s3]
            [sparkboard.server.images :as images]
            [ring.util.response :as resp]
            [clojure.set :as set]
            [sparkboard.datalevin :as dl]
            [sparkboard.server.images :as images]
            [sparkboard.validate :as sv]
            [sparkboard.util :as u]
            [re-db.api :as db]
            [clj-http.client :as http]
            [sparkboard.query-params :as query-params]))

(defn init-db!
  "Ensure current bucket is in the db"
  []
  (let [bucket-name (:s3/bucket-name env/config)]
    (when (and bucket-name
               (not (:s3/bucket-name (dl/entity [:s3/bucket-name bucket-name]))))
      (dl/transact! [(select-keys env/config
                                  [:s3/bucket-name
                                   :s3/bucket-host
                                   :s3/endpoint])]))))

(init-db!)


(defn link-asset
  "Return a database entitiy for an asset that is an external link"
  [url]
  {:asset/id (dl/to-uuid :asset url)
   :asset/provider :asset.provider/link
   :asset/link url})


(def amazonica-config
  ;; s3 (or compatible store) config for amazonica
  (-> env/config
      (u/select-as  {:s3/access-key-id     :access-key
                     :s3/secret-access-key :secret-key
                     :s3/endpoint          :endpoint
                     :s3/region            :region})
      (update :region #(or % "auto"))))

(defn s3-presigned-url
  "Returns a presigned url for an s3 object which expires in 1 week"
  [bucket-name key]
  (str
   (s3/generate-presigned-url amazonica-config
                              {:bucket-name bucket-name
                               :method "GET"
                               :expires (java.util.Date. (+ (System/currentTimeMillis) (* 7 24 60 60 1000)))
                               :key key}
                              (java.util.Date. (+ (System/currentTimeMillis) (* 7 24 60 60 1000))))))

(defn upload-to-s3! [bucket-name object-key size content-type input-stream]
  (s3/put-object amazonica-config
                 bucket-name
                 object-key
                 (io/input-stream input-stream)
                 {:content-type content-type
                  :cache-control "max-age=31536000"
                  :content-length size}))

(defn upload-handler [req _ _]
  (let [{{:keys [filename tempfile content-type size]} "files"} (:multipart-params req)]
    (sv/assert size [:and 'number? [:<= (* 20 1000 1000)]]
               {:message "File size must be less than 20MB."})
    (sv/assert content-type
               [:enum "image/jpeg" "image/png" "image/gif" "image/svg+xml" "image/webp"]
               {:message "Sorry, that image format isn't supported."})
    (let [bucket-name (:s3/bucket-name env/config)
          asset-id (random-uuid)
          asset  {:asset/provider :asset.provider/s3
                  :asset/id asset-id
                  :asset/size size
                  :asset/content-type content-type
                  :s3/bucket [:s3/bucket-name bucket-name]
                  :entity/created-at (java.util.Date.)
                  :asset/created-by [:entity/id (:entity/id (:account req))]}]
      (upload-to-s3!  bucket-name
                     (str asset-id)
                     size
                     content-type
                     (io/input-stream tempfile))
      (dl/transact! [asset])
      {:status 200
       :body {:asset/id asset-id}})))

(defn resizable? [content-type]
  (not (contains? #{"image/svg+xml" "image/gif"} content-type)))

(defn ensure-content-type!
  "Returns content-type for asset, reading via a HEAD request if necessary."
  [asset]
  (or
   ;; read content-type from db.
   (:asset/content-type asset)

      ;; determine content-type via HEAD request & write to db.
   (try (when-let [content-type (and (= :asset.provider/link (:asset/provider asset))
                                     (some-> (:asset/link asset)
                                             http/head
                                             :headers
                                             (get "Content-Type")))]
          (dl/transact! [{:asset/id           (:asset/id asset)
                          :asset/content-type content-type}])
          [(:asset/id asset) content-type])
        (catch Exception e
          ;;  if this fails, mark asset as unavailable and return 404.
          (dl/transact! [[:db/add [:asset/id (:asset/id asset)] :asset.link/failed? true]])
          (throw (ex-info "Unable to determine content type" {:status 404}))))))

(defn asset-src [asset]
  (case (:asset/provider asset)
    :asset.provider/link (:asset/link asset)
    :asset.provider/s3 (str (-> asset :s3/bucket :s3/bucket-host) "/" (:asset/id asset))))

(defn variant-src [asset variant]
  (str (-> variant :s3/bucket :s3/bucket-host) "/" (:asset/id asset) (:asset.variant/param-string variant)))

(defn url-stream [url]
  (let [conn (.openConnection (java.net.URL. url))]
    (io/input-stream (.getInputStream conn))))

(defn serve-variant 
  ([asset query-params] (serve-variant asset query-params true))
  ([asset query-params find-or-create?]
   (tap> [:asset (seq asset)])
   (or (when-let [param-string (and (resizable? (ensure-content-type! asset))
                                    (images/param-string query-params))] 
         (if-let [variant (u/find-first (:asset/variants asset) (comp #{param-string} :asset.variant/param-string))]
           (resp/redirect (variant-src asset variant) 302)
          ;; upload new variant
           (when find-or-create?
             (let [source (url-stream (asset-src asset))
                   bytes (images/format source query-params)
                   asset-id (:asset/id asset)
                   bucket-name (:s3/bucket-name env/config)]
               (upload-to-s3! bucket-name
                              (str asset-id "?" param-string)
                              (count bytes)
                              "image/webp"
                              bytes)
               (dl/transact! [[:db/add [:asset/id asset-id] :asset/variants "variant"]
                              {:db/id "variant"
                               :asset.variant/param-string param-string
                               :s3/bucket [:s3/bucket-name bucket-name]}])
               (serve-variant (dl/entity [:asset/id asset-id]) query-params false))))) 
       (resp/redirect (asset-src asset) 302))))



(comment
  
  (def a (->> (dl/q '[:find [?asset ...]
                      :where [?asset :asset/link ?url]
                      :in $])
              (drop 1)
              (take 1)
              (map dl/entity)
              first))
  (:asset/variants a)
  (serve-variant a {:width 101 :height 100 :mode :bound})
  (dl/transact! [[:db/add [:asset/id (:asset/id a)] :asset/variants "variant"]
                 {:db/id "variant"
                  :asset.variant/param-string "abc"
                  :s3/bucket [:s3/bucket-name (:s3/bucket-name env/config)]}])
  
  
  
  )

(defn serve-asset [req {:keys [asset/id query-params]}]
  (when-let [asset (dl/entity [:asset/id id])]
    (cond (:asset.link/failed? asset) (resp/not-found "Asset not found")
          (seq query-params) (serve-variant asset query-params)
          :else
          (resp/redirect (asset-src asset) 302))))

(comment
  (clojure.java.io/input-stream (clojure.java.io/file
                                 (clojure.java.io/resource "public/images/logo-2023.png")))
  (def file (clojure.java.io/file
             (clojure.java.io/resource "public/images/logo-2023.png")))
  (s3-upload file "logo-2023.png")
  (slurp (str (s3-presigned-url "logo-2023.png"))))

;; issues 
;; - GIFS, SVGs...?
;; - validate content type?



