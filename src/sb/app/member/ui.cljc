(ns sb.app.member.ui
  (:require [sb.app.asset.ui :as asset.ui]
            [sb.app.field.ui :as field.ui]
            [sb.app.member.data :as data]
            [sb.app.views.ui :as ui]
            [sb.app.views.ui :as ui]
            [sb.color :as color]
            [sb.i18n :refer [t]]
            [sb.routing :as routing]))

(ui/defview show
  {:route       "/m/:member-id"
   :view/router :router/modal}
  [params]
  (let [{:as          member
         :member/keys [tags
                       custom-tags
                       account]} (data/show {:member-id (:member-id params)})
        {:keys [:account/display-name
                :image/avatar]} account]
    [:div
     [:h1 display-name]
     ;; avatar
     ;; fields
     (when-let [tags (seq (concat tags custom-tags))]
       [:section [:h3 (t :tr/tags)]
        (into [:ul]
              (map (fn [{:tag/keys [label color]}]
                     [:li {:style (when color {:background-color color})} label]))
              tags)])
     (when avatar [:img {:src (asset.ui/asset-src avatar :card)}])]))

(defn show-tag [{:keys [tag/label tag/color] :or {color "#dddddd"}}]
  [:div.tag-sm
   {:key   label
    :style {:background-color color
            :color            (color/contrasting-text-color color)}}
   label])

(ui/defview card
  {:key (fn [_ member] (str (:entity/id member)))}
  [{:keys [entity/member-fields]} member]
  (let [{:keys [entity/field-entries
                member/tags
                member/custom-tags
                member/account]} member
        {:keys [account/display-name]} account]
    [:a.flex-v.hover:bg-gray-100.rounded-lg
     {:href (routing/entity-path member 'ui/show)}


     [:div.flex.relative.gap-3.items-center.p-2.cursor-default.flex-auto
      [ui/avatar {:size 10} account]
      [:div.line-clamp-2.leading-snug.flex-grow.flex-v.gap-1 display-name
       [:div.flex.flex-wrap.gap-1
        (map show-tag tags)
        (map show-tag custom-tags)]]]
     ;; show card entries on hover?
     #_(when-let [entries (seq
                          (for [{:as field :keys [field/id
                                                  field/label]} member-fields
                                :let [entry (get field-entries id)]
                                :when entry]
                            (assoc entry :field-entry/field field)))]
       [:div.text-gray-500.ml-14.pl-1
        (map field.ui/show-entry:card entries)])]))
