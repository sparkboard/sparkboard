(ns sb.app.org.admin-ui
  (:require [sb.app.views.ui :as ui]
            [sb.app.org.data :as data]
            [sb.app.form.ui :as form.ui]
            [sb.app.entity.ui :as entity.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.domain-name.ui :as domain-name.ui]
            [sb.app.views.header :as header]
            [sb.i18n :refer [t]]))

(ui/defview settings
  {:route "/o/:org-id/settings"}
  [{:as params :keys [org-id]}]
  (let [org (data/settings params)]
    [:<>
     (header/entity org (list (entity.ui/settings-button org)))
     [:div {:class form.ui/form-classes}
      (entity.ui/use-persisted-attr org :entity/title field.ui/text-field)
      (entity.ui/use-persisted-attr org :entity/description field.ui/prose-field)
      (entity.ui/use-persisted-attr org :entity/domain-name domain-name.ui/domain-field)
      ;; TODO - uploading an image does not work
      (entity.ui/use-persisted-attr org :image/avatar field.ui/image-field {:label (t :tr/image.logo)})

      ]]))