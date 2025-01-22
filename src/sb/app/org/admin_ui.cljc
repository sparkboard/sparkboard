(ns sb.app.org.admin-ui
  (:require [sb.app.entity.ui :as entity.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.org.data :as data]
            [sb.app.views.header :as header]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [t]]))

(ui/defview settings
  {:route "/o/:org-id/settings"}
  [{:as params :keys [org-id]}]
  (let [org (data/settings params)
        use-attr (fn [attr & [props]]
                   (entity.ui/persisted-attr org attr (merge {:field/can-edit? true} props)))]
    [:<>
     (header/entity org nil)
     [:div {:class form.ui/form-classes}
      (use-attr :entity/title)
      (use-attr :entity/description)
      (use-attr :entity/domain-name)
      (use-attr :image/avatar {:field/label (t :tr/logo)})
      (use-attr :image/sub-header {:field/label (t :tr/image.sub-header)})
      (use-attr :image/background {:field/label (t :tr/image.background)})

      (use-attr :entity/public?)
      ]]))
