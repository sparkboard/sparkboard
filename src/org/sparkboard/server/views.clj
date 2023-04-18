(ns org.sparkboard.server.views
  (:require
   [hiccup.util]
   [mhuebert.cljs-static.html :as html]
   [mhuebert.cljs-static.assets :as assets]
   [ring.util.response :as ring.response]
   [tools.sparkboard.transit :as transit]
   [clojure.java.io :as io])
  (:import (java.time Instant)))

(defn spa-page [config]
  (-> {:body (str (html/html-page {:title "Sparkboard"
                                   :styles [{:href "https://unpkg.com/tachyons@4.10.0/css/tachyons.min.css"}
                                            {:href "https://fonts.googleapis.com/css?family=Merriweather"}
                                            ".skeleton {font-family: Merriweather, cursive;}"]
                                   :scripts/body [{:src (str "/js/compiled/app.js?v="
                                                             (or (-> (io/resource "public/js/main.js")
                                                                     assets/try-slurp
                                                                     assets/md5)
                                                                 (.toEpochMilli (Instant/now))))}]
                                   :body [[:script#SPARKBOARD_CONFIG {:type "application/transit+json"}
                                           (hiccup.util/raw-string (transit/write config))]
                                          [:div#web]]}))}
      (ring.response/content-type "text/html")
      (ring.response/status 200)))
