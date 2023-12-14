(ns sparkboard.app.field-entry
  (:require [sparkboard.ui :as ui]
            [sparkboard.util :as u]
            [clojure.string :as str]
            [sparkboard.query :as q]
            [sparkboard.validate :as validate]
            [re-db.api :as db]
            [yawn.hooks :as h]
            [inside-out.forms :as forms]
            [sparkboard.ui.radix :as radix]
            [sparkboard.schema :as sch]))


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

(defmulti entry-value (fn [field entry] (:field/type field)))

(defmethod entry-value nil [_ _] nil)

(defmethod entry-value :field.type/image-list [_field entry]
  (when-let [images (u/guard (:image-list/images entry) seq)]
    {:image-list/images images}))

(defmethod entry-value :field.type/video [_field entry]
  (when-let [value (u/guard (:video/url entry) (complement str/blank?))]
    {:video/url value}))

(defmethod entry-value :field.type/select [_field entry]
  (when-let [value (u/guard (:select/value entry) (complement str/blank?))]
    {:select/value value}))

(defmethod entry-value :field.type/link-list [_field entry]
  (when-let [value (u/guard (:link-list/links entry) seq)]
    {:link-list/links value}))

(defmethod entry-value :field.type/prose [_field entry]
  (when-let [value (u/guard (:prose/string entry) (complement str/blank?))]
    {:prose/string value
     :prose/format (:prose/format entry)}))

(q/defx save-entry! [{:keys [account-id]} parent-id field-id entry]
  (validate/assert-can-edit! parent-id account-id)
  (let [field   (db/entity (sch/wrap-id field-id))
        parent  (db/entity (sch/wrap-id parent-id))
        entries (assoc (get parent :entity/field-entries) field-id entry)]
    (validate/assert (db/touch field) :field/as-map)
    (validate/assert entry :field-entry/as-map)
    (db/transact! [[:db/add (:db/id parent) :entity/field-entries entries]])
    {:txs [{:entity/id            (:entity/id parent)
            :entity/field-entries entries}]}))

(ui/defview show-entry
  {:key (comp :entity/id :field)}
  [{:keys [parent field entry can-edit?]}]
  (let [value  (entry-value field entry)
        ?field (h/use-memo #(forms/field :init (entry-value field entry) :label (:field/label field)))
        props  {:label     (:label field)
                :can-edit? can-edit?
                :on-save   (partial save-entry! nil (:entity/id parent) (:entity/id field))}]
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