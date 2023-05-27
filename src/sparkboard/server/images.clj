(ns sparkboard.server.images
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [sparkboard.query-params :as query-params])
  (:import [com.sksamuel.scrimage ImmutableImage]
           [com.sksamuel.scrimage.webp WebpWriter]))

  ;; https://sksamuel.github.io/scrimage/
  ;; https://sksamuel.github.io/scrimage/webp/

(defn param-string [{:keys [mode
                            width
                            height
                            autocrop]}]
  (when mode
    (query-params/query-string  (sort-by key {:mode     (name mode) 
                                              :width    (str width)
                                              :height   (str height)
                                              :autocrop (when autocrop true)}))))

(defn format
  "Accepts input (must be coercable to InputStream) and options:"
  [input {:keys [mode
                 width
                 height
                 autocrop]}]
  (-> (ImmutableImage/loader)
      (.fromStream (io/input-stream input))
      (cond-> autocrop (.autocrop))
      (as-> img
            (case mode
              :cover (.cover img width height)
              :bound (.bound img width height)))
      (.bytes WebpWriter/DEFAULT)))

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
  
  (let [img-dir (io/file (io/resource "public/images"))
        from-file (io/file img-dir "logo-2023.svg")]
    (slurp from-file)
    #_(-> (ImmutableImage/loader)
          (.fromFile from-file) 
          
          ))
  

  ;; operations: 
  
  (s3-upload file "logo-2023.png")
  (slurp (str (s3-presigned-url "logo-2023.png")))
  
  
  )


;; general plan for serving resized assets. 

;; 1. accept options:
;;   - width x height
;;   - modes [cover, bound]
;;   - autocrop <boolean>
;;   - 

