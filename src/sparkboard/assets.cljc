(ns sparkboard.assets
  (:require #?(:clj [sparkboard.server.env :as env])
            #?(:clj [amazonica.aws.s3 :as s3])
            [clojure.set :as set]
            [re-db.api :as db]
            [sparkboard.datalevin :as dl]
            [sparkboard.validate :as sv]))

#?(:clj
   (defn external-link [url]
     {:asset/id (dl/to-uuid :asset url)
      :asset/provider :asset.provider/external-link
      :src url}))

;; user uploads will be stored using an s3-compatible service.
;; we'll use the same service and bucket for all uploads.

#?(:clj
   (def amazonica-config (-> (:s3 env/config)
                             (set/rename-keys {:access-key-id :access-key
                                               :secret-access-key :secret-key})
                             (assoc :region "auto"))))

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
       (tap> [size (type size)])
       (let [{:keys [bucket-name serving-host]} amazonica-config
             asset-id (random-uuid)
             object-key (str asset-id "-" filename)
             asset {:asset/provider :asset.provider/s3
                    :asset/id asset-id
                    :asset/content-type content-type
                    :asset/size size
                    :s3/bucket-name bucket-name
                    :entity/created-at (java.util.Date.)
                    :entity/created-by [:entity/id (:entity/id (:account req))]
                    :src (str serving-host "/" object-key)}]
         (s3/put-object amazonica-config
                        bucket-name
                        object-key
                        tempfile)
         (dl/transact! [asset])
         {:status 200
          :body {:db/id [:asset/id asset-id]
                 :src (:src asset)}}))))

(comment
  (s3-upload (clojure.java.io/file
               (clojure.java.io/resource "public/images/logo-2023.png"))
             "logo-2023.png")
  (slurp (str (s3-presigned-url "logo-2023.png"))))

;; TODO
;; [x] set up r2 storage
;; [x] set up db schema
;; [x] fn to upload file to s3
;; [ ] endpoint to upload file to s3
;; [ ] UI/form widget to upload file & include a reference to it in another entity
;;     - max file size
;;     - file type restrictions
;;     - upload progress
;;     - upload error handling
;; [ ] image resizing
;;     - store a reference to generated sizes in db
;;     - name the sizes, or whitelist params?
