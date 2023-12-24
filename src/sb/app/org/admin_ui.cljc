(ns sb.app.org.admin-ui
  (:require [sb.app.entity.ui :as entity.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.org.data :as data]
            [sb.app.views.header :as header]
            [sb.app.views.ui :as ui]))

(ui/defview settings
  {:route "/o/:org-id/settings"}
  [{:as params :keys [org-id]}]
  (let [org (data/settings params)]
    [:<>
     (header/entity org (list (entity.ui/settings-button org)))
     [:div {:class form.ui/form-classes}
      (entity.ui/use-persisted-field org :entity/title )
      (entity.ui/use-persisted-field org :entity/description)
      (entity.ui/use-persisted-field org :entity/domain-name)
      ;; TODO - uploading an image does not work
      (entity.ui/use-persisted-field org :image/avatar)

      ]]))