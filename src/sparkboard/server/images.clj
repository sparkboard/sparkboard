(ns sparkboard.server.images
  (:refer-clojure :exclude [format])
  (:require [clojure.java.io :as io]          
            [sparkboard.validate :as validate]
            [sparkboard.util :as u]
            [sparkboard.query-params :as query-params])
  (:import [com.sksamuel.scrimage ImmutableImage]
           [com.sksamuel.scrimage.webp WebpWriter]))

(def MAX-WIDTH 2000)
(def MAX-HEIGHT 2000)

  ;; https://sksamuel.github.io/scrimage/
  ;; https://sksamuel.github.io/scrimage/webp/

(def options-schema [:and [:map {:closed true}
                           [:op [:enum "cover" "bound"]]
                           [:width {:optional true} [:<= MAX-WIDTH]]
                           [:height {:optional true} [:<= MAX-HEIGHT]]
                           [:autocrop {:optional true} :boolean]]])

(defn normalize-options [options]
  (-> options
      (u/update-some  {:width    #(Integer. %)
                       :height   #(Integer. %)
                       :autocrop #(when % true)})
      (u/prune)
      (validate/assert options-schema)))

(defn params-string 
  "Normalized param string with stable order"
  [options]
  (if (string? options)
    options
    (when (seq options)
      (let [options (normalize-options options)]
        (-> (sort-by key options)
            query-params/query-string 
            (subs 1))))))

(comment 
  (params-string {:op "bound" :width 100 :height 100})
  (params-string {:op "cover ":width 100 }))

(defn format
  "Accepts input (must be coercable to InputStream) and options:"
  [input options]
  (let [{:keys [op
                width
                height
                autocrop]} (normalize-options options)]
    (-> (ImmutableImage/loader)
        (.fromStream (io/input-stream input))
        (cond-> autocrop (.autocrop))
        (as-> img
              (case op
                ;; don't expose any upsizing methods
                "cover" (.cover img width height)
                "bound" (.bound img (or width MAX-WIDTH) (or height MAX-HEIGHT))))
        (.bytes WebpWriter/DEFAULT))))

(comment 
  (require '[libpython-clj2.python :as py])
  )