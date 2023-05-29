(ns sparkboard.server.images
  (:refer-clojure :exclude [format])
  (:require [clojure.java.io :as io]          
            [sparkboard.validate :as validate]
            [sparkboard.util :as u]
            [sparkboard.query-params :as query-params])
  (:import [com.sksamuel.scrimage ImmutableImage]
           [com.sksamuel.scrimage.webp WebpWriter]))

  ;; https://sksamuel.github.io/scrimage/
  ;; https://sksamuel.github.io/scrimage/webp/

(def options-schema [:map {:closed true}
                     [:mode {:optional true} [:enum "cover" "bound"]]
                     [:width {:optional true} [:<= 2000]]
                     [:height {:optional true} [:<= 2000]]
                     [:autocrop {:optional true} :boolean]])

(defn normalize-options [options]
  (-> options
      (u/update-some  {:width    #(Integer. %)
                       :height   #(Integer. %)
                       :autocrop #(when % true)})
      (u/prune)
      (update :mode #(if % (name %) "bound"))))

(defn params-string 
  "Normalized param string with stable order"
  [options]
  (when (seq options)
    (let [options (normalize-options options)]
      (validate/assert options options-schema)
      (subs (query-params/query-string  
             (sort-by key (u/update-some options {:width str :height str})))
            1))))

(comment 
  (params-string {:width 100 :height 100}))

(defn format
  "Accepts input (must be coercable to InputStream) and options:"
  [input options]
  (let [{:keys [mode
                width
                height
                autocrop]} (normalize-options options)]
    (-> (ImmutableImage/loader)
        (.fromStream (io/input-stream input))
        (cond-> autocrop (.autocrop))
        (as-> img
              (case mode
                ;; don't expose any upsizing methods
                "cover" (.cover img width height)
                "bound" (.bound img width height)))
        (.bytes WebpWriter/DEFAULT))))

(comment 
  (require '[libpython-clj2.python :as py])
  )