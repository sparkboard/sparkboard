(ns sparkboard.app.field
  (:require [sparkboard.schema :as sch :refer [s- ?]]))

(sch/register!
  {:image/url             {s- :http/url}

   :field/hint            {s- :string},
   :field/id              sch/unique-uuid
   :field/label           {s- :string},
   :field/default-value   {s- :string}
   :field/options         {s- (? [:sequential :field/option])},
   :field/option          {s- [:map {:closed true}
                               (? :field-option/color)
                               (? :field-option/value)
                               :field-option/label]}
   :field/order           {s- :int},
   :field/required?       {s- :boolean},
   :field/show-as-filter? {:doc "Use this field as a filtering option"
                           s-   :boolean},
   :field/show-at-create? {:doc "Ask for this field when creating a new entity"
                           s-   :boolean},
   :field/show-on-card?   {:doc "Show this field on the entity when viewed as a card"
                           s-   :boolean},
   :field/type            {s- [:enum
                               :field.type/images
                               :field.type/video
                               :field.type/select
                               :field.type/link-list
                               :field.type/prose
                               :field.type/prose]}

   :field-entry/id        sch/unique-uuid
   :field-entry/field     (sch/ref :one)
   :field-entry/value     {s- [:multi {:dispatch 'first}
                               [:field.type/images
                                [:tuple 'any?
                                 [:sequential [:map {:closed true} :image/url]]]]
                               [:field.type/link-list
                                [:tuple 'any?
                                 [:map {:closed true}
                                  [:link-list/items
                                   [:sequential :link-list/link]]]]]
                               [:field.type/select
                                [:tuple 'any?
                                 [:map {:closed true}
                                  [:select/value :string]]]]
                               [:field.type/prose
                                [:tuple 'any?
                                 :prose/as-map]]
                               [:field.type/video :video/value
                                [:tuple 'any? :video/entry]]]}
   :field-entry/as-map    {s- [:map {:closed true}
                               :field-entry/id
                               :field-entry/field
                               :field-entry/value]}
   :link-list/link        {:todo "Tighten validation after cleaning up db"
                           s-    [:map {:closed true}
                                  (? [:text :string])
                                  [:url :string]]}
   :field-option/color    {s- :html/color},
   :field-option/default  {s- :string},
   :field-option/label    {s- :string},
   :field-option/value    {s- :string},
   :video/type            {s- [:enum
                               :video.type/youtube-id
                               :video.type/youtube-url
                               :video.type/vimeo-url]}
   :video/value           {s- :string}
   :video/entry           {s- [:map {:closed true}
                               :video/value
                               :video/type]}
   :field/as-map          {:doc  "Description of a field."
                           :todo ["Field specs should be definable at a global, org or board level."
                                  "Orgs/boards should be able to override/add field.spec options."
                                  "Field specs should be globally merged so that fields representing the 'same' thing can be globally searched/filtered?"]
                           s-    [:map {:closed true}
                                  :field/id
                                  :field/order
                                  :field/type
                                  (? :field/hint)
                                  (? :field/label)
                                  (? :field/options)
                                  (? :field/required?)
                                  (? :field/show-as-filter?)
                                  (? :field/show-at-create?)
                                  (? :field/show-on-card?)]}})