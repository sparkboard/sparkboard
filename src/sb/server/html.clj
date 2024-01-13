(ns sb.server.html
  (:require
   [hiccup.util]
   [mhuebert.cljs-static.html :as html]
   [mhuebert.cljs-static.assets :as assets]
   [ring.util.response :as ring.response]
   [sb.transit :as transit]
   [sb.app.views.layouts :as layouts]
   [clojure.java.io :as io]
   [sb.server.env :as env])
  (:import (java.time Instant)))

(defn static-path [prefix path]
  (str path "?v=" (if (= "dev" (env/config :env))
                    (str (random-uuid))
                    (or (-> (str prefix path)
                            io/resource
                            assets/try-slurp
                            assets/md5)
                        (.toEpochMilli (Instant/now))))))

(defn page [content]
  (-> {:body (str (html/html-page {:title "Sparkboard"
                                   :styles [{:href (static-path "public" "/sparkboard.css")}]
                                   :body [(cond-> content (string? content) hiccup.util/raw-string)]}))}
      (ring.response/content-type "text/html")
      (ring.response/status 200)))

(defn formatted-text [content]
  (page [:div.prose.max-w-2xl.mx-auto.my-16 content]))

(defn two-col [left & right]
  [:div.grid.grid-cols-1.md:grid-cols-2.h-screen
   [:div.flex-v.justify-center.bg-secondary.order-last.md:order-first
    left]
   (into [:div.flex-v.shadow-sm.relative.text-center.gap-6.justify-center]
         right)])

(defn error [e {:keys [status
                       title
                       description
                       detail]}]
  (let [title (or title (case status 404 "Not found" "Oops!"))
        status (or status 500)
        description (or description (case status
                                      404
                                      "Sorry, we can't find that page."
                                      "Something went wrong."))
        detail (or detail (ex-message e))]
    (page
     (layouts/two-col
      [:img.mx-auto {:class "w-1/2" :src "/images/error-2023.svg"}]
      [:h1.text-3xl.md:text-5xl title]
      [:p.text-muted-txt description]
      [:div.flex-center.gap-x-6
       [:a.btn.btn-primary.p-4.text-lg {:href "/"} "← Home"]]
      (when detail
        [:pre.code.bg-secondary.p-4.text-sm.whitespace-pre-wrap.mx-4 detail])
      (when status
        [:p.text-base.font-semibold.text-indigo-600 status])))))

(defn app-page [{:as options :keys [tx schema content]}]
  (-> {:body (str (html/html-page {:title "Sparkboard"
                                   :styles [{:href (static-path "public" "/sparkboard.css")}]
                                   :scripts/body [{:src (static-path "public" "/js/app.js")}]
                                   :body [[:script {:type "application/re-db"}
                                           (->> (select-keys options [:tx :schema])
                                                transit/write
                                                hiccup.util/raw-string)]
                                          [:div#app-root content]]}))}
      (ring.response/content-type "text/html")
      (ring.response/status 200)))