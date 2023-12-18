(ns sparkboard.app.field-entry.data
  (:require [clojure.string :as str]
            [re-db.api :as db]
            [sparkboard.query :as q]
            [sparkboard.schema :as sch]
            [sparkboard.util :as u]
            [sparkboard.validate :as validate]))

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
