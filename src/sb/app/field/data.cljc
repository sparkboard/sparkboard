(ns sb.app.field.data
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [re-db.api :as db]
            [sb.app.views.ui :as ui]
            [sb.authorize :as az]
            [sb.i18n :refer [t]]
            [sb.icons :as icons]
            [sb.query :as q]
            [sb.schema :as sch :refer [? s-]]
            [sb.server.datalevin :as dl]
            [sb.util :as u]
            [sb.validate :as validate]
            [yawn.hooks :as h]
            [yawn.view :as v]))

;; TODO

;; SETTINGS (boards)
;; - re-order fields
;; - confirm before removing a field (radix alert?)
;; - re-order options
;; - add a new field
;;   - (def blanks {<type> <template>})
;;   - an entity/add-multi! endpoint for adding a new cardinality/many entity
;;     (in this case, a new :field which is pointed to by :board/member-fields or :board/project-fields)
;;   - entity/remove-multi! endpoint; use db/isComponent to determine whether the target is retracted?
;; - remove a field
;;   - an entity/retract-multi! endpoint

;; ENTITIES (members/projects)
;; - displaying the value of a field
;; - editing a field's value
;; - CARDS: showing fields on cards
;; - REGISTRATION: showing fields during membership creation



(sch/register!
  {:image/url                   {s- :http/url}
   :field/id                    {s- :uuid},
   :field/hint                  {s- :string},
   :field/label                 {s- :string},
   :field/default-value         {s- :string}
   :field/options               {s- (? [:sequential :field/option])},
   :field/option                {s- [:map {:closed true}
                                     (? :field-option/color)
                                     (? :field-option/value)
                                     :field-option/label]}
   :field/required?             {s- :boolean},
   :field/show-as-filter?       {:doc "Use this field as a filtering option"
                                 s-   :boolean},
   :field/show-at-registration? {:doc "Ask for this field when creating a new entity"
                                 s-   :boolean},
   :field/show-on-card?         {:doc "Show this field on the entity when viewed as a card"
                                 s-   :boolean},
   :field/type                  {s- [:enum
                                     :field.type/image-list
                                     :field.type/video
                                     :field.type/select
                                     :field.type/link-list
                                     :field.type/prose]}

   :link-list/link              {:todo "Tighten validation after cleaning up db"
                                 s-    [:map {:closed true}
                                        (? :link/label)
                                        :link/url]}
   :link/label                  {s- :string}
   :link/url                    {s- :http/url}
   :field-option/color          {s- :html/color},
   :field-option/default        {s- :string},
   :field-option/label          {s- :string},
   :field-option/value          {s- :string},
   :video/url                   {s- :string}
   :image-list/images           {s- [:sequential [:map {:closed true} :entity/id]]}
   :link-list/links             {s- [:sequential :link-list/link]}
   :select/value                {s- :string}
   :field-entry/as-map          {s- [:map {:closed true}
                                     (? :image-list/images)
                                     (? :video/url)
                                     (? :select/value)
                                     (? :link-list/links)
                                     (? :prose/format)
                                     (? :prose/string)]}
   :field/published?            {s- :boolean}
   :field/as-map                {:doc  "Description of a field."
                                 :todo ["Field specs should be definable at a global, org or board level."
                                        "Orgs/boards should be able to override/add field.spec options."
                                        "Field specs should be globally merged so that fields representing the 'same' thing can be globally searched/filtered?"]
                                 s-    [:map {:closed true}
                                        :field/id
                                        :field/type
                                        (? :field/published?)
                                        (? :field/hint)
                                        (? :field/label)
                                        (? :field/options)
                                        (? :field/required?)
                                        (? :field/show-as-filter?)
                                        (? :field/show-at-registration?)
                                        (? :field/show-on-card?)]}})

(def field-keys [:field/hint
                 :field/id
                 :field/label
                 :field/default-value
                 {:field/options [:field-option/color
                                  :field-option/value
                                  :field-option/label]}
                 :field/required?
                 :field/show-as-filter?
                 :field/show-at-registration?
                 :field/show-on-card?
                 :field/type])

(def field-types {:field.type/prose      {:icon        icons/bars-3-bottom-left
                                          :field/label (t :tr/text)}
                  :field.type/select     {:icon        icons/queue-list
                                          :field/label (t :tr/menu)}
                  :field.type/video      {:icon        icons/play-circle
                                          :field/label (t :tr/video)}
                  :field.type/link-list  {:icon        icons/link-2
                                          :field/label (t :tr/links)}
                  :field.type/image-list {:icon        icons/photo
                                          :field/label (t :tr/image)}
                  })

(defn blank? [color]
  (or (empty? color) (= "#ffffff" color) (= "rgb(255, 255, 255)" color)))

(defn re-order [xs source side destination]
  {:post [(= (count %) (count xs))]}
  (let [out (reduce (fn [out x]
                      (if (= x destination)
                        (into out (case side :before [source destination]
                                             :after [destination source]))
                        (conj out x)))
                    []
                    (remove #{source} xs))]
    (when-not (= (count out) (count xs))
      (throw (ex-info "re-order failed, destination not found" {:source source :destination destination})))
    out))

(defn element-center-y [el]
  #?(:cljs
     (j/let [^js {:keys [y height]} (j/call el :getBoundingClientRect)]
       (+ y (/ height 2)))))

(defn orderable-props
  [{:keys [group-id
           id
           on-move
           !should-drag?]}]
  #?(:cljs
     (let [transfer-data (fn [e data]
                           (j/call-in e [:dataTransfer :setData] (str group-id)
                                      (pr-str data)))

           receive-data  (fn [e]
                           (try
                             (ui/read-string (j/call-in e [:dataTransfer :getData] (str group-id)))
                             (catch js/Error e nil)))

           data-matches? (fn [e]
                           (some #{(str group-id)} (j/get-in e [:dataTransfer :types])))
           [active-drag set-drag!] (h/use-state nil)
           [active-drop set-drop!] (h/use-state nil)
           !should-drag? (h/use-ref false)]
       [{:on-mouse-down #(reset! !should-drag? true)
         :on-mouse-up   #(reset! !should-drag? false)}
        {:draggable     true
         :data-dragging active-drag
         :data-dropping active-drop
         :on-drag-over  (j/fn [^js {:as e :keys [clientY currentTarget]}]
                          (j/call e :preventDefault)
                          (when (data-matches? e)
                            (set-drop! (if (< clientY (element-center-y currentTarget))
                                         :before
                                         :after))))
         :on-drag-leave (fn [^js e]
                          (j/call e :preventDefault)
                          (set-drop! nil))
         :on-drop       (fn [^js e]
                          (.preventDefault e)
                          (set-drop! nil)
                          (when-let [source (receive-data e)]
                            (on-move {:destination id
                                      :source      source
                                      :side        active-drop})))
         :on-drag-end   (fn [^js e]
                          (set-drag! nil))
         :on-drag-start (fn [^js e]
                          (if @!should-drag?
                            (do
                              (set-drag! true)
                              (transfer-data e id))
                            (.preventDefault e)))}
        (when active-drop
          (v/x [:div.absolute.bg-focus-accent
                {:class ["h-[4px] z-[99] inset-x-0 rounded"
                         (case active-drop
                           :before "top-[-2px]"
                           :after "bottom-[-2px]" nil)]}]))])))

(q/defx add-field
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} e a new-field]
  (let [e               (sch/wrap-id e)
        entity (dl/entity e)
        _ (validate/assert-can-edit! account-id entity)
        existing-fields (a entity)
        field           (assoc new-field :field/id (dl/new-uuid :field))]
    (validate/assert field :field/as-map)
    (db/transact! [[:db/add e a (conj existing-fields field)]])
    {:field/id (:field/id field)}))

(q/defx remove-field
  {:prepare [az/with-account-id!]}
  [{:keys [account-id]} parent-id a field-id]
  (let [parent (db/entity (sch/wrap-id parent-id))]
    (validate/assert-can-edit! account-id parent)
    (db/transact! [[:db/add (:db/id parent) a (->> (get parent a)
                                                   (remove (comp #{field-id} :field/id))
                                                   vec)]])
    {}))

(defmulti entry-value (comp :field/type :field-entry/field))

(defmethod entry-value nil [_] nil)

(defmethod entry-value :field.type/image-list [entry]
  (when-let [images (u/guard (:image-list/images entry) seq)]
    {:image-list/images images}))

(defmethod entry-value :field.type/video [entry]
  (when-let [value (u/guard (:video/url entry) (complement str/blank?))]
    {:video/url value}))

(defmethod entry-value :field.type/select [entry]
  (when-let [value (u/guard (:select/value entry) (complement str/blank?))]
    {:select/value value}))

(defmethod entry-value :field.type/link-list [entry]
  (when-let [value (u/guard (:link-list/links entry) seq)]
    {:link-list/links value}))

(defmethod entry-value :field.type/prose [entry]
  (when-let [value (u/guard (:prose/string entry) (complement str/blank?))]
    {:prose/string value
     :prose/format (or (:prose/format entry) :prose.format/markdown)}))

(q/defx save-entry! [{:keys [account-id]} parent-id field-id entry]
  (let [field   (db/entity (sch/wrap-id field-id))
        parent  (db/entity (sch/wrap-id parent-id))
        _ (validate/assert-can-edit! account-id parent)
        entries (assoc (get parent :entity/field-entries) field-id entry)]
    (validate/assert (db/touch field) :field/as-map)
    (validate/assert entry :field-entry/as-map)
    (db/transact! [[:db/add (:db/id parent) :entity/field-entries entries]])
    {:txs [{:entity/id            (:entity/id parent)
            :entity/field-entries entries}]}))

(comment
  *e
  (db/touch (db/entity [:entity/id (java.util.UUID/fromString "a4a3ebc8-5f01-3107-be70-9857db054f52")])))