(ns sparkboard.server.uploads
  (:require [sparkboard.server.env :as env]
            [amazonica.aws.s3 :as s3]))

;; store assets on an external service to simplify provisioning for the server itself.
;; (compatible services are available everywhere; we'll use Cloudflare R2.)
;; for now we'll put `bucket` in config and assume we always use the same one.

(def s3-config (:s3 env/config))

(defn upload-file-to-s3 [^java.io.File file object-key]
  (let [{:keys [access-key-id secret-access-key endpoint bucket-name]} s3-config]
    (s3/put-object {:access-key access-key-id
                    :secret-key secret-access-key
                    :endpoint endpoint}
                   bucket-name
                   object-key
                   file)))
