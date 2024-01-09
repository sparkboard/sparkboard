(ns sb.app.org.admin-ui
  (:require [sb.app.entity.ui :as entity.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.org.data :as data]
            [sb.app.views.header :as header]
            [sb.app.views.ui :as ui]
            [sb.i18n :as i]))

(ui/defview settings
  {:route "/o/:org-id/settings"}
  [{:as params :keys [org-id]}]
  (let [org (data/settings params)]
    [:<>
     (header/entity org nil)
     [:div {:class form.ui/form-classes}
      (entity.ui/use-persisted-attr org :entity/title)
      (entity.ui/use-persisted-attr org :entity/description)
      (entity.ui/use-persisted-attr org :entity/domain-name)
      (entity.ui/use-persisted-attr org :image/avatar {:field/label (i/t :tr/logo)})

      ]]))