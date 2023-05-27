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

(def !default-bucket 
  (delay (init-db!)
         (:db/id (dl/entity [:s3/bucket-name (:s3/bucket-name env/config)]))))


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
                  :s3/bucket @!default-bucket
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

(defn find-or-create-variant! 
  "A variant contains a reference to an s3 bucket and the normalized parameter string."
  [bucket params]
  (or (dl/q '[:find ?e .
              :where
              [?e :s3/bucket ?bucket]
              [?e :asset.variant/params ?params]
              :in $ ?bucket ?params]
            bucket params)
      (-> (dl/transact! [{:db/id -1 
                          :s3/bucket bucket 
                          :asset.variant/params params}]) 
          :tempids 
          (get -1))))

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
  (str (-> variant :s3/bucket :s3/bucket-host) "/" (:asset/id asset) "_" (:asset.variant/params variant)))

(defn url-stream [url]
  (let [conn (.openConnection (java.net.URL. url))]
    (io/input-stream (.getInputStream conn))))

(defn serve-variant 
  [asset query-params]
  (tap> [:asset (seq asset)])
  (resp/redirect
   (if-let [params (and (resizable? (ensure-content-type! asset))
                        (images/params-string query-params))]
     (if-let [variant (u/find-first (:asset/variants asset) (comp #{params} :asset.variant/params))]
       (variant-src asset variant)
       (let [asset-stream (url-stream (asset-src asset))
             formatted-bytes  (images/format asset-stream query-params)
             asset-id (:asset/id asset)
             _ (upload-to-s3! (:s3/bucket-name env/config)
                              (str asset-id "_" params)
                              (count formatted-bytes)
                              "image/webp"
                              formatted-bytes)
             variant-id (find-or-create-variant! @!default-bucket params)]
         (dl/transact! [[:db/add [:asset/id asset-id] :asset/variants variant-id]])
         (variant-src asset (dl/entity variant-id))))
     (asset-src asset))
   302))

(comment
  

  
  (defn all-assets []
    (->> (dl/q '[:find [?asset ...]
                 :where [?asset :asset/link ?url]
                 :in $])
         (map dl/entity)))
  
  (sparkboard.routes/path-for :asset/serve :asset/id (:asset/id (first (all-assets)))
                              :query {:width 100 :height 100})

  (dl/transact! (for [?v (dl/q '[:find [?v ...]
                                 :where [?v :asset.variant/params]])]
                  [:db/retractEntity ?v]))

  (dl/q '[:find [?variant ...]
          :where [?variant :asset.variant/params _]])

  (serve-variant (nth (all-assets) 2)
                 {:width  101
                  :height 100
                  :mode   :bound})
  (dl/transact! [[:db/add [:asset/id (:asset/id a)] :asset/variants "variant"]
                 {:db/id                "variant"
                  :asset.variant/params "abc"
                  :s3/bucket            [:s3/bucket-name (:s3/bucket-name env/config)]}])
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



