(ns sparkboard.app.project.new-flow
  (:require [re-db.api :as db]
            [sparkboard.authorize :as az]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.ui :as ui]
            [sparkboard.routes :as routes]
            [promesa.core :as p]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.ui.icons :as icons]
            [sparkboard.validate :as validate]
            [sparkboard.query :as q]
            [sparkboard.app.entity :as entity]
            [sparkboard.ui.header :as header]
            [sparkboard.app.board :as board]
            [sparkboard.app.project :as project]
            [yawn.view :as v]))


(q/defx db:new!
  {:prepare [az/with-account-id!]}
  [{:as what :keys [account-id board-id project]}]
  (prn what)
  (validate/assert project [:map {:closed true} :entity/title :project/board])
  ;; TODO authorization
  (let [project (-> project
                    ;; Auth: board allows projects to be created by current user (must be a member)
                    (dl/new-entity :project :by account-id))]
    (db/transact! [project]))
  (select-keys project [:entity/id]))

(def form (v/from-element :form.flex.flex-col.items-stretch.gap-6.items-start.outline-6.outline.outline-black.rounded-lg.p-6.flex.flex-col.gap-6))
(def submit (v/from-element :input.btn.btn-primary.px-6.py-3.self-start))
(def tag (v/from-element :div.inline-flex.items-center.gap-2.text-sm.outline.outline-1.outline-gray-300.rounded-lg.px-4.py-2.hover:bg-gray-100.cursor-pointer))
(def tag-detail (v/from-element :div.text-sm.outline-gray-300.flex))
(def tag-detail-label (v/from-element :div.flex.items-center.gap-2.outline-1.outline-gray-300.outline.h-10.px-3.rounded-l-lg.whitespace-nowrap.flex-none {:class "min-w-[150px]"}))
(def tag-detail-input (v/from-element :input.h-10.flex-auto.px-3.form-text.rounded-r-lg.rounded-l-none {:placeholder "Add details..."}))
(def tag-icon (v/from-element :div))

(ui/defview start
  {:route ["/b/" ['entity/id :board-id] "/new"]}
  [{:keys [board-id]}]
  (let [field      :div.flex.flex-col.gap-3
        form-label :div.text-primary.text-sm.font-medium
        form-hint  :div.text-gray-500.text-sm]
    [:<>
     [header/entity (board/db:board {:board-id board-id})]
     [:div.p-body.flex.flex-col.gap-8

      [ui/show-markdown "Creating a new project is a multi-step process."]

      (ui/with-form [!project {:entity/title  ?title
                               :project/board board-id}]
        [form
         {:ref       (ui/use-autofocus-ref)
          :on-submit (fn [e]
                       (.preventDefault e)
                       (ui/with-submission [result (db:new! {:project @!project})
                                            :form !project]
                         (prn result)
                         (routes/set-path! 'sparkboard.app.board/show {:board-id board-id})))}
         [:h3 (tr :tr/new-project)]


         [field
          [form-label "Project title"]
          [ui/text-field ?title]]

         ;; here is where we would loop through the "project fields" which are defined
         ;; in this board's settings.

         [field
          [form-label "What is the problem you're trying to solve?"]
          [:textarea.form-text]]
         [field
          [form-label "How do you plan to solve it?"]
          [:textarea.form-text]]

         [submit {:type "submit" :value (tr :tr/next)}]])


      [ui/show-markdown "The **Looking For** field is where teams ask for help from the community. "]

      (ui/with-form [!project {:entity/title  ?title
                               :project/board board-id}]
        [form
         {:ref       (ui/use-autofocus-ref)
          :on-submit (fn [e]
                       (.preventDefault e)
                       (ui/with-submission [result (db:new! {:project @!project})
                                            :form !project]
                         (prn result)
                         (routes/set-path! 'sparkboard.app.board/show {:board-id board-id})))}

         [field
          [form-label "Looking for:"]
          [:input.form-text.w-60 {:placeholder "What do you need?"}]
          [form-hint "Suggestions"]
          [:div.flex.flex-wrap.gap-2
           [tag "Designer" [tag-icon "🎨"]]
           [tag "Programmer" [tag-icon "💻"]]
           [tag "Project Manager" [tag-icon "📅"]]
           [tag "Beta testers" [tag-icon "🧪"]]
           [tag "Mentoring" [tag-icon "🧠"]]
           [tag "Investor intros" [tag-icon "💰"]]]]])

      [ui/show-markdown "Example with content - when you click on a tag, you can add details:"]

      (ui/with-form [!project {:entity/title  ?title
                               :project/board board-id}]
        [form
         {:ref       (ui/use-autofocus-ref)
          :on-submit (fn [e]
                       (.preventDefault e)
                       (ui/with-submission [result (db:new! {:project @!project})
                                            :form !project]
                         (prn result)
                         (routes/set-path! 'sparkboard.app.board/show {:board-id board-id})))}

         [field
          [form-label "Looking for:"]
          [:div.grid.gap-3
           [:input.form-text.w-60 {:placeholder "What do you need?"}]
           [tag-detail [tag-detail-label "Programmer" [tag-icon "💻"]] [tag-detail-input {:value "We plan to use Rust"}]]
           [tag-detail [tag-detail-label "Beta testers" [tag-icon "🧪"]] [tag-detail-input {:value "Android and iOS, anyone interested in unicorns!"}]]
           [tag-detail [tag-detail-label "Mentoring" [tag-icon "🧠"]] [tag-detail-input]]]
          [form-hint "Suggestions"]
          [:div.flex.flex-wrap.gap-2
           [tag "Designer" [tag-icon "🎨"]]
           [tag " Project Manager" [tag-icon "📅"]]
           [tag "Investor intros" [tag-icon "💰"]]]]])]]))
