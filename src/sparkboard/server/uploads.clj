(ns sparkboard.server.uploads
  (:require [clojure.set :as set]
            [sparkboard.server.env :as env]
            [amazonica.aws.s3 :as s3]))

;; user uploads will be stored using an s3-compatible service.
;; we'll use the same service and bucket for all uploads.

(def amazonica-config (-> (:s3 env/config)
                          (set/rename-keys {:access-key-id :access-key
                                            :secret-access-key :secret-key})
                          (assoc :region "auto")))

(defn s3-upload [^java.io.File file object-key]
  (s3/put-object amazonica-config
                 (:bucket-name amazonica-config)
                 object-key
                 file))

(defn s3-presigned-url [object-key]
  (str
   (s3/generate-presigned-url amazonica-config
                              {:bucket-name (:bucket-name amazonica-config)
                               :method "GET"
                               :expires (java.util.Date. (+ (System/currentTimeMillis) (* 7 24 60 60 1000)))
                               :key object-key}
                              (java.util.Date. (+ (System/currentTimeMillis) (* 7 24 60 60 1000))))))

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
