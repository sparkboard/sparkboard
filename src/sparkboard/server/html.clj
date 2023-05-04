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

(defn static-html [content]
  (-> {:body (str (html/html-page {:title "Sparkboard"
                                   :styles [{:href "/sparkboard.css"}]
                                   :body [[:div.prose.max-w-2xl.mx-auto.my-16
                                           (hiccup.util/raw-string content)]]}))}
      (ring.response/content-type "text/html")
      (ring.response/status 200)))

(defn single-page-html [{:as options :keys [tx schema content]}]
  (-> {:body (str (html/html-page {:title "Sparkboard"
                                   :styles [{:href "/sparkboard.css"}]
                                   :scripts/body [{:src (str "/js/app.js?v=" (invalidation-token "public/js/app.js"))}]
                                   :body [[:script {:type "application/re-db"}
                                           (->> (select-keys options [:tx :schema])
                                                transit/write
                                                hiccup.util/raw-string)]
                                          [:div#web content]]}))}
      (ring.response/content-type "text/html")
      (ring.response/status 200)))
