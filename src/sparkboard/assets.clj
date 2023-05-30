(ns sparkboard.assets
  (:require [clojure.java.io :as io]
            [sparkboard.server.env :as env]
            [amazonica.aws.s3 :as s3]
            [sparkboard.server.images :as images]
            [ring.util.response :as resp]
            [sparkboard.datalevin :as dl]
            [sparkboard.server.images :as images]
            [sparkboard.validate :as sv]
            [sparkboard.util :as u]
            [clj-http.client :as http]
            [sparkboard.validate :as validate]))

(defn init-db!
  "Ensure current bucket is in the db"
  []
  (when (:s3 env/config) 
    (dl/transact! [(merge {:asset.provider/type :asset.provider/s3}
                          (select-keys (:s3 env/config)
                                       [:s3/bucket-name
                                        :s3/bucket-host
                                        :s3/endpoint]))])))

(def !default-provider
  (delay (init-db!)
         (dl/entity [:asset.provider.s3/endpoint+bucket-name ((juxt :s3/endpoint :s3/bucket-name)
                                                              (:s3 env/config))])))


(defn link-asset
  "Return a database entitiy for an asset that is an external link"
  [url]
  {:asset/id (dl/to-uuid :asset url)
   :asset/link url})


(def amazonica-config
  ;; s3 (or compatible store) config for amazonica
  (-> (:s3 env/config)
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

(def upload-schema [:map {:closed true}
                    [:provider 'some?]
                    [:object-key :string]
                    [:size [:<= (* 20 1000 1000)]]
                    [:content-type 'string?]
                    [:stream 'some?]])

(defn upload-to-provider! [{:as upload :keys [provider object-key size content-type stream]}]
  (validate/assert upload upload-schema)
  (case (:asset.provider/type provider)
    :asset.provider/s3 (upload-to-s3! (:s3/bucket-name provider)
                                      object-key
                                      size
                                      content-type
                                      stream)))

(def asset-schema [:map {:closed true}
                   [:asset/provider 'int?]
                   [:asset/id 'uuid? ]
                   [:asset/size 'number?]
                   [:asset/content-type 'string?]
                   [:entity/created-by 'vector?]
                   [:entity/created-at 'some?] 
                  
                   ])

(defn upload-handler [req _ _]
  (let [{{:keys [filename tempfile content-type size]} "files"} (:multipart-params req)]
    (sv/assert size [:and 'number? [:<= (* 20 1000 1000)]]
               {:message "File size must be less than 20MB."})
    (sv/assert content-type
               [:enum "image/jpeg" "image/png" "image/gif" "image/svg+xml" "image/webp"]
               {:message "Sorry, that image format isn't supported."})
    (let [asset-id (random-uuid)
          provider @!default-provider]
      (upload-to-provider! {:provider     provider
                            :object-key   (str asset-id)
                            :size         size
                            :content-type content-type
                            :stream       (io/input-stream tempfile)})
      (dl/transact! [(-> #:asset{:provider          (:db/id provider)
                                 :id          asset-id
                                 :size              size
                                 :content-type      content-type
                                 :entity/created-at (java.util.Date.)
                                 :entity/created-by        [:entity/id (:entity/id (:account req))]}
                         (validate/assert asset-schema))])
      {:status 200
       :body {:asset/id asset-id}})))

(defn resizable? [content-type]
  (not (contains? #{"image/svg+xml" "image/gif"} content-type)))

(def variant-schema [:map {:closed true}
                     [:asset.variant/provider 'int?]
                     [:asset.variant/param-string 'string?]
                     [:asset.variant/content-type 'string?]
                     [:asset.variant/generated-via 'string?]])

(defn find-or-create-variant! 
  "A variant contains a reference to a provider and the normalized parameter string."
  [provider query-params]
  (let [params (images/params-string query-params)]
    (or (dl/q '[:find ?variant .
                :where
                [?variant :asset.variant/provider ?provider]
                [?variant :asset.variant/param-string ?params]
                :in $ ?provider ?params]
              (:db/id provider) params)
        (-> (dl/transact! [(-> #:asset.variant{:provider (:db/id provider)
                                               :param-string params
                                               :content-type "image/webp"
                                               :generated-via "scrimage"}
                               (validate/assert variant-schema)
                               (assoc :db/id -1))]) 
            :tempids 
            (get -1)))))

(defn ensure-content-type!
  "Returns content-type for asset, reading via a HEAD request if necessary."
  [asset]
  (or
   ;; read content-type from db.
   (:asset/content-type asset)

      ;; determine content-type via HEAD request & write to db.
   (try (when-let [content-type (some-> (:asset/link asset)
                                        http/head
                                        :headers
                                        (get "Content-Type"))]
          (dl/transact! [[:db/add [:asset/id (:asset/id asset)] :asset/content-type content-type] ])
          [(:asset/id asset) content-type])
        (catch Exception e
          ;;  if this fails, mark asset as unavailable and return 404.
          (dl/transact! [[:db/add [:asset/id (:asset/id asset)] :asset/link-failed? true]])
          (throw (ex-info "Unable to determine content type" {:status 404}))))))

(defn provider-link [provider asset]
  (case (:asset.provider/type provider)
    :asset.provider/s3 (str (:s3/bucket-host provider)
                            "/"
                            (:asset/id asset))))

(defn asset-link [asset]
  (or (:asset/link asset)
      (provider-link (:asset/provider asset) asset)))

(defn variant-link [asset variant]
  (tap> [:variant-link {:variant variant
                        :asset asset}])
  (str (provider-link (:asset.variant/provider variant) asset)
       "_"
       (:asset.variant/param-string variant)))

(defn url-stream [url]
  (let [conn (.openConnection (java.net.URL. url))]
    (io/input-stream (.getInputStream conn))))

(defn stream-bytes [is]
  (let [baos (java.io.ByteArrayOutputStream.)]
    (io/copy is baos)
    (.toByteArray baos)))

(defn serve-variant 
  [asset query-params]
  (resp/redirect
   (if-let [params (and (resizable? (ensure-content-type! asset))
                        (images/params-string query-params))]
     (if-let [variant (u/find-first (:asset/variants asset) (comp #{params} :asset.variant/param-string))]
       (variant-link asset variant)
       (let [asset-stream (url-stream (asset-link asset))
             formatted-bytes  (images/format asset-stream query-params)
             asset-id (:asset/id asset)
             _ (upload-to-provider! {:provider @!default-provider
                                     :object-key (str asset-id "_" params)
                                     :size (count formatted-bytes)
                                     :content-type "image/webp"
                                     :stream (io/input-stream formatted-bytes)})
             variant-id (find-or-create-variant! @!default-provider params)]
         (dl/transact! [[:db/add [:asset/id asset-id] :asset/variants variant-id]])
         (variant-link asset (dl/entity variant-id))))
     (asset-link asset))
   302))

(defn serve-asset [req {:keys [asset/id query-params]}]
  (when-let [asset (dl/entity [:asset/id id])]
    (cond (:asset/link-failed? asset) (resp/not-found "Asset not found")
          (seq query-params) (serve-variant asset query-params)
          :else
          (resp/redirect (asset-link asset) 302))))


