(ns sparkboard.app.project
  (:require #?(:clj [sparkboard.app.member :as member])
            #?(:clj [sparkboard.server.datalevin :as dl])
            [promesa.core :as p]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.validate :as validate]
            [sparkboard.ui :as ui]
            [sparkboard.websockets :as ws]
            [sparkboard.entity :as entity]
            [re-db.api :as db]
            [yawn.view :as v]
            [sparkboard.ui.radix :as radix]))

#?(:clj
   (defn db:new!
     {:endpoint {:post ["/p/new"]}}
     [req {:as params project :body}]
     (validate/assert project [:map {:closed true} :entity/title])
     ;; auth: user is member of board & board allows members to create projects
     (db/transact! [(-> project
                        (assoc :project/board [:entity/id (:entity/id params)])
                        (dl/new-entity :project :by (:db/id (:account req))))])
     ;; what to return?
     {:status 201}
     ))

(ui/defview new
            {:endpoint    {:view ["/p/" "new"]}
             :view/target :modal}
            [{:as params :keys [route]}]
            (ui/with-form [!project {:entity/title ?title}]
                          [:div
                           [:h3 (tr :tr/new-project)]
                           (ui/show-field ?title)
                           [:button {:on-click #(p/let [res (routes/POST `db:new! @!project)]
                                                  (when-not (:error res)
                                                    (routes/set-path! ['sparkboard.app.board/read params])))}
                            (tr :tr/create)]]))

(defn youtube-embed [video-id]
  [:iframe#ytplayer {:type        "text/html" :width 640 :height 360
                     :frameborder 0
                     :src         (str "https://www.youtube.com/embed/" video-id)}])

(defn video-field [[kind v]]
  (case kind
    :video/youtube-id (youtube-embed v)
    :video/youtube-url [:a {:href v} "youtube video"]
    :video/vimeo-url [:a {:href v} "vimeo video"]
    {kind v}))

(comment
  (video-field [:field.video/youtube-sdgurl "gMpYX2oev0M"])
  )

#?(:clj
   (defn db:read
     {:endpoint  {:query ["/p/" ['uuid :project-id]]}
      :authorize (fn [req {:as params :keys [project-id]}]
                   (member/member:read-and-log! project-id (:db/id (:account req)))
                   params)}
     [{:keys [project-id]}]
     (db/pull `[{:project/board ~entity/fields}
                ~@entity/fields
                :project/sticky?]
              [:entity/id project-id])))

(def btn (v/from-element :div.btn.btn-transp.border-2.py-2.px-3))
(def hint (v/from-element :div.flex.items-center.text-sm {:class "text-primary/70"}))
(def chiclet (v/from-element :div.rounded.px-2.py-1 {:class "bg-primary/5 text-primary/90"}))

(ui/defview read
            {:endpoint    {:view ["/p/" ['uuid :project-id]]}
             :view/target :modal}
            [params]
            (let [{:as           project
                   :entity/keys  [title
                                  description
                                  video]
                   :project/keys [board
                                  badges]} (ws/pull! [:*
                                                      {:project/board entity/fields}]
                                                     [:entity/id (:project-id params)])]
              [:<>
               #_[ui/entity-header board]
               [:div.p-body.flex.flex-col.gap-2
                [:h1.font-bold.text-xl title]
                (ui/show-prose description)
                (when-let [badges badges]
                  [:section
                   (into [:ul]
                         (map (fn [bdg] [:li.rounded.bg-badge.text-badge-txt.py-1.px-2.text-sm.inline-flex (:badge/label bdg)]))
                         badges)])
                [:section.flex.flex-col.gap-2.items-start
                 [:h3.uppercase.text-sm (tr :tr/support-project)]
                 [:div.flex.gap-2

                  (for [label ["💰 Donate"
                               "🤝 Join our team"
                               "🤲 Lend a hand"
                               "? Contribute something..."
                               "❤️ Love"]]
                    [btn {:key label} label])]]

                #_[:section.flex.flex-col.gap-2.items-start
                   [:h3.uppercase.text-sm (tr :tr/support-project)]

                   [:div.flex.gap-2
                    [btn "🧠 Mentor"]
                    [hint {:class "flex gap-2"}
                     [chiclet "Finance"]
                     [chiclet "Marketing"]]]

                   [:div.flex.gap-2
                    [btn "💰 Donate"]
                    [hint "Currently raising a seed round"]]

                   [:div.flex.gap-2
                    [btn "🤝 Join our team"]
                    [hint {:class "flex gap-2"} [chiclet "Designer"]]]

                   [:div.flex.gap-2
                    [btn "🔗 Share"]
                    [hint "Send our video to your friends"]]

                   [:div.flex.gap-2
                    [btn "❤️ Love"]]]
                #_[:section.flex.flex-col.gap-2.items-start
                   [:h3.uppercase.text-sm (tr :tr/support-project)]

                   [:div.flex.gap-2
                    [btn "🤲 Lend a hand"]
                    [hint {:class "flex gap-2"}
                     [chiclet "Skill A"]
                     [chiclet "Task B"]]]

                   [:div.flex.gap-2
                    [btn "💰 Donate"]
                    [hint "We are seeking funds to _"]]

                   [:div.flex.gap-2
                    [btn "🤝 Join our team"]
                    [hint {:class "flex gap-2"} [chiclet "Designer"]]]

                   [:div.flex.gap-2
                    [btn "🔗 Share"]
                    [hint "Send our video to your friends"]]

                   [:div.flex.gap-2
                    [btn "❤️ Love"]]]
                (when-let [vid video]
                  [:section [:h3 (tr :tr/video)]
                   [video-field vid]])]]))
