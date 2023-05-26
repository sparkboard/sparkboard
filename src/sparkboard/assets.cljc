(ns sparkboard.assets
  (:require #?(:clj [sparkboard.server.env :as env])
            #?(:clj [amazonica.aws.s3 :as s3])
            #?(:clj [sparkboard.server.images :as images])
            [clojure.set :as set]
            [sparkboard.datalevin :as dl] 
            [sparkboard.validate :as sv]
            [sparkboard.util :as u]
            [re-db.api :as db]))


#?(:clj 
   ;; ensure current bucket is in db
   ;; Note: this could cause problems if the  
   (let [bucket-name (:s3/bucket-name env/config)]
     (when (and bucket-name
                (not (:s3/bucket-name (dl/entity [:s3/bucket-name bucket-name]))))
       (dl/transact! [(select-keys env/config
                                   [:s3/bucket-name
                                    :s3/bucket-host
                                    :s3/endpoint])]))))

#?(:clj
   (defn link-asset [url]
     {:asset/id (dl/to-uuid :asset url)
      :asset/provider :asset.provider/link
      :asset/link url}))

;; user uploads will be stored using an s3-compatible service.
;; we'll use the same service and bucket for all uploads.

#?(:clj
   (def amazonica-config (-> env/config
                             (u/select-as  {:s3/access-key-id     :access-key
                                            :s3/secret-access-key :secret-key
                                            :s3/endpoint          :endpoint
                                            :s3/region            :region}) 
                             (update :region #(or % "auto")))))

#?(:clj
   (defn s3-presigned-url [bucket-name key]
     (str
       (s3/generate-presigned-url amazonica-config
                                  {:bucket-name bucket-name
                                   :method "GET"
                                   :expires (java.util.Date. (+ (System/currentTimeMillis) (* 7 24 60 60 1000)))
                                   :key key}
                                  (java.util.Date. (+ (System/currentTimeMillis) (* 7 24 60 60 1000)))))))

#?(:clj
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
         (s3/put-object amazonica-config
                        bucket-name
                        (str asset-id)
                        (clojure.java.io/input-stream tempfile)
                        {:content-type content-type
                         :cache-control "max-age=31536000"
                         :content-length size})
         (dl/transact! [asset])
         {:status 200
          :body {:asset/id asset-id}}))))

#?(:cljs (def !bucket-host (delay (db/get :env/config :s3/bucket-host))))
#?(:cljs (defn src [asset]
           (when asset
             (or (:asset/link asset)
                 (str @!bucket-host "/" (:asset/id asset))))))

(comment
  (clojure.java.io/input-stream (clojure.java.io/file
                                 (clojure.java.io/resource "public/images/logo-2023.png")))
  (def file (clojure.java.io/file
             (clojure.java.io/resource "public/images/logo-2023.png")))
  (s3-upload file "logo-2023.png")
  (slurp (str (s3-presigned-url "logo-2023.png"))))