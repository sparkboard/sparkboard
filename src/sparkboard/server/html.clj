(ns sparkboard.server.html
  (:require
   [hiccup.util]
   [mhuebert.cljs-static.html :as html]
   [mhuebert.cljs-static.assets :as assets]
   [ring.util.response :as ring.response]
   [sparkboard.transit :as transit]
   [clojure.java.io :as io])
  (:import (java.time Instant)))

(defn single-page-html [config account]
  (-> {:body (str (html/html-page {:title "Sparkboard"
                                   :styles [{:href "https://unpkg.com/tachyons@4.10.0/css/tachyons.min.css"}
                                            {:href "https://fonts.googleapis.com/css?family=Merriweather"}
                                            ".skeleton {font-family: Merriweather, cursive;}"]
                                   :scripts/body [{:src (str "/js/compiled/app.js?v="
                                                             (or (-> (io/resource "public/js/main.js")
                                                                     assets/try-slurp
                                                                     assets/md5)
                                                                 (.toEpochMilli (Instant/now))))}]
                                   :body [[:script {:type "application/re-db"}
                                           (hiccup.util/raw-string
                                            (transit/write
                                             {:tx (cond-> [(assoc config :db/id :env/config)]
                                                          account
                                                          (conj (assoc account :db/id :env/account)))}))]
                                          [:div#web]]}))}
      (ring.response/content-type "text/html")
      (ring.response/status 200)))
