(ns sparkboard.app.field-entry.ui
  (:require [inside-out.forms :as forms]
            [sparkboard.app.field-entry.data :as data]
            [sparkboard.ui :as ui]
            [sparkboard.ui.radix :as radix]
            [yawn.hooks :as h]))

(ui/defview show-select [?field {:field/keys [label options]} entry]
  [:div.flex-v.gap-2
   [ui/input-label label]
   [radix/select-menu {:value      (:select/value @?field)
                       :id         (str (:entity/id entry))
                       :read-only? (:can-edit? ?field)
                       :options    (->> options
                                        (map (fn [{:field-option/keys [label value color]}]
                                               {:text  label
                                                :value value}))
                                        doall)}]])

(ui/defview show-entry
  {:key (comp :entity/id :field)}
  [{:keys [parent field entry can-edit?]}]
  (let [value  (data/entry-value field entry)
        ?field (h/use-memo #(forms/field :init (data/entry-value field entry) :label (:field/label field)))
        props  {:label     (:label field)
                :can-edit? can-edit?
                :on-save   (partial data/save-entry! nil (:entity/id parent) (:entity/id field))}]
    (case (:field/type field)
      :field.type/video [ui/video-field ?field props]
      :field.type/select [ui/select-field ?field (merge props
                                                        {:wrap            (fn [x] {:select/value x})
                                                         :unwrap          :select/value
                                                         :persisted-value value
                                                         :options         (:field/options field)})]
      :field.type/link-list [ui/pprinted value props]
      :field.type/image-list [ui/images-field ?field props]
      :field.type/prose [ui/prose-field ?field props]
      (str "no match" field))))