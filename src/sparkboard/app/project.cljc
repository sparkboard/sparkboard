(ns sparkboard.app.project
  (:require #?(:clj [sparkboard.app.member :as member])
            #?(:clj [sparkboard.server.datalevin :as dl])
            [promesa.core :as p]
            [sparkboard.authorize :as az]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.routes :as routes]
            [sparkboard.schema :as sch :refer [s- ?]]
            [sparkboard.validate :as validate]
            [sparkboard.ui :as ui]
            [sparkboard.websockets :as ws]
            [sparkboard.entity :as entity]
            [re-db.api :as db]
            [yawn.view :as v]
            [sparkboard.ui.radix :as radix]))

(comment
  (first (db/where [:project/badges]))
  db/*conn*
  )
(sch/register!
  (merge

    {:social/sharing-button {s- [:enum
                                 :social.sharing-button/facebook
                                 :social.sharing-button/twitter
                                 :social.sharing-button/qr-code]}}

    {:request/text {:doc "Free text description of the request"
                    s-   :string}
     :request/map  {s- [:map {:closed true} :request/text]}}

    {:project/board             (sch/ref :one)
     :project/open-requests     {:doc "Currently active requests for help"
                                 s-   [:sequential :request/map]},
     :project/team-complete?    {:doc "Project team marked sufficient"
                                 s-   :boolean}
     :project/approved?         {:doc "Set by an admin when :board/new-projects-require-approval? is enabled. Unapproved projects are hidden."
                                 s-   :boolean}
     :project/badges            {:doc "A badge is displayed on a project with similar formatting to a tag. Badges are ad-hoc, not defined at a higher level.",
                                 s-   [:vector :content/badge]}
     :project/number            {:doc  "Number assigned to a project by its board (stored as text because may contain annotations)",
                                 :todo "This could be stored in the board entity, a map of {project, number}, so that projects may participate in multiple boards"
                                 s-    :string}
     :project/admin-description {:doc "A description field only writable by an admin"
                                 s-   :prose/as-map}
     :entity/archived?          {:doc "Marks a project inactive, hidden."
                                 s-   :boolean}
     :project/sticky?           {:doc "Show project with border at top of project list"
                                 s-   :boolean}
     :project/as-map            {s- [:map {:closed true}
                                     :entity/id
                                     :entity/kind
                                     :project/board
                                     (? :entity/archived?)
                                     (? :entity/field-entries)
                                     (? :entity/video)
                                     (? :entity/created-by)
                                     (? :entity/deleted-at)
                                     (? :entity/modified-by)
                                     :entity/title
                                     :entity/created-at
                                     :entity/updated-at
                                     (? :member/_entity)
                                     (? [:project/card-classes {:doc          "css classes for card"
                                                                :to-deprecate true}
                                         [:sequential :string]])
                                     (? :project/approved?)
                                     (? :project/badges)
                                     (? :project/number)
                                     (? :project/admin-description)
                                     (? :project/sticky?)
                                     (? :project/open-requests)
                                     (? :entity/description)
                                     (? :project/team-complete?)]}}))

#?(:clj
   (defn db:new!
     {:endpoint {:post ["/p/new"]}
      :prepare [az/with-account-id!]}
     [req {:keys [account-id] project :body}]
     (validate/assert project [:map {:closed true} :entity/title])
     (db/transact! [(-> project
                        ;; Auth: board allows projects to be created by current user (must be a member)
                        (dl/new-entity :project :by account-id))])
     ;; what to return?
     {:status 201}
     ))

(ui/defview new
  {:route    ["/p/" "new"]
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
     {:endpoint {:query true}
      :prepare  [az/with-account-id
                 (member/member:log-visit! :project-id)]}
     [{:keys [project-id]}]
     (db/pull `[{:project/board ~entity/fields}
                ~@entity/fields
                :project/sticky?]
              project-id)))

(def btn (v/from-element :div.btn.btn-transp.border-2.py-2.px-3))
(def hint (v/from-element :div.flex.items-center.text-sm {:class "text-primary/70"}))
(def chiclet (v/from-element :div.rounded.px-2.py-1 {:class "bg-primary/5 text-primary/90"}))

(ui/defview read
  {:route       ["/p/" ['entity/id :project-id]]
   :view/target :modal}
  [params]
  (let [{:as           project
         :entity/keys  [title
                        description
                        video]
         :project/keys [board
                        badges]} (ws/pull! [:*
                                            {:project/board entity/fields}]
                                           (:project-id params))]
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
