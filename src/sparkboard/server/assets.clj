(ns sparkboard.server.assets
  (:require [amazonica.aws.s3 :as s3]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [ring.util.response :as resp]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.server.env :as env]
            [sparkboard.server.images :as images]
            [sparkboard.util :as u]
            [sparkboard.validate :as sv]
            [sparkboard.validate :as validate]
            [re-db.api :as db]))

(defn init-db!
  "Ensure current bucket is in the db"
  []
  (when (:s3 env/config)
    (db/transact! [(merge {:asset.provider/type :asset.provider/s3}
                          (select-keys (:s3 env/config)
                                       [:s3/bucket-name
                                        :s3/bucket-host
                                        :s3/endpoint]))])))

(def !default-provider
  (delay (init-db!)
         (dl/entity [:asset.provider.s3/endpoint+bucket-name ((juxt :s3/endpoint :s3/bucket-name)
                                                              (:s3 env/config))])))

(defn provider? [x] (:asset.provider/type x))

(defn link-asset
  "Return a database entitiy for an asset that is an external link"
  [url]
  {:asset/id   (dl/to-uuid :asset url)
   :asset/link url})


(def amazonica-config
  ;; s3 (or compatible store) config for amazonica
  (-> (:s3 env/config)
      (u/select-as {:s3/access-key-id     :access-key
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
                                :method      "GET"
                                :expires     (java.util.Date. (+ (System/currentTimeMillis) (* 7 24 60 60 1000)))
                                :key         key}
                               (java.util.Date. (+ (System/currentTimeMillis) (* 7 24 60 60 1000))))))

(defn upload-to-s3! [bucket-name object-key size content-type input-stream]
  (s3/put-object amazonica-config
                 bucket-name
                 object-key
                 (io/input-stream input-stream)
                 {:content-type   content-type
                  :cache-control  "max-age=31536000"
                  :content-length size}))

(defn upload-to-provider! [{:as upload :keys [provider object-key size content-type stream]}]
  {:pre [(provider? provider)
         (string? object-key)
         (<= size (* 20 1000 1000))
         (string? content-type)
         stream]}
  (case (:asset.provider/type provider)
    :asset.provider/s3 (upload-to-s3! (:s3/bucket-name provider)
                                      object-key
                                      size
                                      content-type
                                      stream)))

(defn upload!
  {:endpoint {:post ["/upload"]}}
  [req _]
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
                                 :id                asset-id
                                 :size              size
                                 :content-type      content-type
                                 :entity/created-at (java.util.Date.)
                                 :entity/created-by [:entity/id (:entity/id (:account req))]}
                         (validate/assert :asset/as-map))])
      {:status 200
       :body   {:asset/id asset-id}})))

(defn resizable? [content-type]
  (not (contains? #{"image/svg+xml" "image/gif"} content-type)))

(defn find-or-create-variant!
  "Returns an asset.variant entity."
  [provider query-params]
  {:pre [(provider? provider)]}
  (let [param-string (images/param-string query-params)
        lookup-ref   [:asset.variant/provider+params [(:db/id provider) param-string]]]
    (or (dl/entity lookup-ref)
        (do (dl/transact! [(-> #:asset.variant{:provider     (:db/id provider)
                                               :param-string param-string}
                               (validate/assert :asset.variant/as-map)
                               (assoc :db/id -1))])
            (dl/entity lookup-ref)))))

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
           (dl/transact! [[:db/add [:asset/id (:asset/id asset)] :asset/content-type content-type]])
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

(defn variant-link!
  [asset query-params]
  {:pre [(dl/entity? asset)]}
  (when-let [param-string (and (resizable? (ensure-content-type! asset))
                               (images/param-string query-params))]
    (if-let [variant (->> (:asset/variants asset)
                          (filter (comp #{param-string} :asset.variant/param-string))
                          first)]
      (variant-link asset variant)
      (let [asset-stream (url-stream (asset-link asset))
            {:keys [bytes content-type]} (images/format asset-stream query-params)
            variant      (find-or-create-variant! @!default-provider query-params)]
        (upload-to-provider! {:provider     @!default-provider
                              :object-key   (str (:asset/id asset) "_" param-string)
                              :size         (count bytes)
                              :content-type content-type
                              :stream       (io/input-stream bytes)})
        (dl/transact! [[:db/add (:db/id asset) :asset/variants (:db/id variant)]])
        (variant-link asset variant)))))

(defn serve-asset
  {:endpoint         {:get ["/assets/" ['uuid :asset/id]]}
   :endpoint/public? true}
  [req {:keys [asset/id query-params]}]
  (if-let [asset (some-> (dl/entity [:asset/id id])
                         (u/guard (complement :asset/link-failed?)))]
    (resp/redirect
      (or
        (variant-link! asset query-params)
        (asset-link asset))
      301)
    (resp/not-found "Asset not found")))


