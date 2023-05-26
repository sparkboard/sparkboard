(ns sparkboard.server.images
  (:require [clojure.java.io :as :io])
  (:import [com.sksamuel.scrimage ImmutableImage]
           [com.sksamuel.scrimage.webp WebpWriter]))

(comment
  ;; use scrimage to format images 
  ;; https://sksamuel.github.io/scrimage/
  ;; https://sksamuel.github.io/scrimage/webp/

  ;; ImmutableImage.loader().fromStream(in)
  ;; ImmutableImage.loader().fromFile(in)
  (io/input-stream (io/file
                    (io/resource "public/images/logo-2023.png")))
  (def file
    (io/file
     (io/resource "public/images/logo-2023.png")))

  (let [img-dir (io/file (io/resource "public/images"))
        from-file (io/file img-dir "logo-2023.png")
        to-file (io/file img-dir "logo-2023-smaller.webp")]
    (-> (ImmutableImage/loader)
        (.fromFile from-file)
        (.max 200 200)
        (.output WebpWriter/DEFAULT to-file)))
  ;; operations: 

  (s3-upload file "logo-2023.png")
  (slurp (str (s3-presigned-url "logo-2023.png")))
  )