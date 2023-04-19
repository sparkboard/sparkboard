(ns sparkboard.server.html
  (:require
   [hiccup.util]
   [mhuebert.cljs-static.html :as html]
   [mhuebert.cljs-static.assets :as assets]
   [ring.util.response :as ring.response]
   [sparkboard.transit :as transit]
   [clojure.java.io :as io])
  (:import (java.time Instant)))

(defn invalidation-token [resource-path]
  (or (-> (io/resource resource-path)
          assets/try-slurp
          assets/md5)
      (.toEpochMilli (Instant/now))))

(defn single-page-html [config account]
  (-> {:body (str (html/html-page {:title "Sparkboard"
                                   :styles [{:href "/sparkboard.css"}]
                                   :scripts/body [{:src (str "/js/app.js?v=" (invalidation-token "public/js/main.js"))}]
                                   :body [[:script {:type "application/re-db"}
                                           (hiccup.util/raw-string
                                            (transit/write
                                             {:tx (cond-> [(assoc config :db/id :env/config)]
                                                          account
                                                          (conj (assoc account :db/id :env/account)))}))]
                                          [:div#web]]}))}
      (ring.response/content-type "text/html")
      (ring.response/status 200)))
