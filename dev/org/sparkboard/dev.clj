(ns org.sparkboard.dev
  (:require [nextjournal.clerk :as clerk]
            [nextjournal.clerk.config :as config]
            [shadow.cljs.devtools.api :as shadow]))

(defn start
  {:shadow/requires-server true}
  []
  (shadow/watch :clerk)
  (swap! config/!resource->url merge {"/js/viewer.js" "http://localhost:3001/clerk.js"})
  (clerk/serve! {:browse? true
                 :port 7999
                 :watch-paths ["dev/org/sparkboard"]}))

(comment
 (start)
 (clerk/clear-cache!))