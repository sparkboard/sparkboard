(ns sparkboard.assets
  (:require #?(:clj [sparkboard.server.env :as env])
            #?(:clj [amazonica.aws.s3 :as s3])
            [clojure.set :as set]
            [re-db.api :as db]
            [sparkboard.datalevin :as dl]))

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
   (defn s3-upload [{:s3/keys [object-key bucket-name]} ^java.io.File file]
     (s3/put-object amazonica-config
                    bucket-name
                    object-key
                    file)))

#?(:clj
   (defn s3-tx [bucket-name filename created-by]
     (let [id (random-uuid)]
       {:asset/provider :asset.provider/s3
        :asset/id id
        :asset/created-by created-by

        :s3/bucket-name bucket-name
        :s3/object-key (str id filename)})))

#?(:clj
   (defn upload-handler [req _ _]
     ;; Q
     ;; what happens to :body-params when we upload a file using fetch?
     (let [{{:keys [filename tempfile]} "files"} (:multipart-params req)]
       (def temp-file tempfile)
       (let [asset (s3-tx (:bucket-name amazonica-config)
                          (str (random-uuid) "_" filename)
                          [:entity/id (:entity/id (:account req))])]
         (prn :upload asset)
         (prn (s3-upload asset tempfile))
         (dl/transact! [asset])
         {:status 200
          :body asset}))))

#?(:cljs
   (def serving-host (delay (:serving-host (db/get :env/config :s3)))))

#?(:cljs
   (defn path [asset]
     (case (:asset/provider asset)
       :s3
       (str @serving-host "/" (:s3/object-key asset))
       (str "No provider" asset))))


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
