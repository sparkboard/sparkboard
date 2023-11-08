(ns sparkboard.app.project.new-flow
  (:require [re-db.api :as db]
            [sparkboard.authorize :as az]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.ui :as ui]
            [sparkboard.routes :as routes]
            [promesa.core :as p]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.validate :as validate]
            [sparkboard.query :as q]
            [sparkboard.app.entity :as entity]
            [sparkboard.ui.header :as header]
            [sparkboard.app.board :as board]
            [sparkboard.app.project :as project]))


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

(ui/defview start
  {:route ["/b/" ['entity/id :board-id] "/new"]}
  [{:keys [board-id]}]
  [:<>
   [header/entity (board/db:board {:board-id board-id})]

   (ui/with-form [!project {:entity/title ?title
                            :project/board board-id}]
     [:form.p-body.flex.flex-col.gap-6.items-start
      {:ref       (ui/use-autofocus-ref)
       :on-submit (fn [e]
                    (.preventDefault e)
                    (ui/with-submission [result (db:new! {:project @!project})
                                         :form !project]
                      (prn result)
                      (routes/set-path! 'sparkboard.app.board/show {:board-id board-id})))}
      [:h3 (tr :tr/new-project)]
      (ui/show-field ?title {:placeholder "Title" :class "placeholder-gray-400"})
      [:input.btn.btn-primary.px-6.py-3 {:type "submit" :value  (tr :tr/create)}]])])
