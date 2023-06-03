(ns sparkboard.entities.project
  (:require [promesa.core :as p]
            [sparkboard.routes :as routes]
            [sparkboard.views.ui :as ui]))

(ui/defview new-view [{:as params :keys [route]}]
  (ui/with-form [!project {:entity/title ?title}]
    [:div
     [:h3 :tr/new-project]
     (ui/show-field ?title)
     [:button {:on-click #(p/let [res (routes/POST route @!project)]
                            (when-not (:error res)
                              (routes/set-path! [:board/read params])))}
      :tr/create]]))

(defn youtube-embed [video-id]
  [:iframe#ytplayer {:type "text/html" :width 640 :height 360
                     :frameborder 0
                     :src (str "https://www.youtube.com/embed/" video-id)}])

(defn video-field [[kind v]]
  (case kind
    :video/youtube-id (youtube-embed v)
    :video/youtube-url [:a {:href v} "youtube video"]
    :video/vimeo-url [:a {:href v} "vimeo video"]
    {kind v}))

(comment
  (video-field [:field.video/youtube-sdgurl "gMpYX2oev0M"])
  )

(ui/defview read-view [{project :data}]
  [:div
   [:h1 (:entity/title project)]
   [:blockquote (:entity/description project)]
   (when-let [badges (:project/badges project)]
     [:section [:h3 :tr/badges]
      (into [:ul]
            (map (fn [bdg] [:li (:badge/label bdg)]))
            badges)])
   (when-let [vid (:entity/video project)]
     [:section [:h3 :tr/video]
      [video-field vid]])])
