(ns sb.app.discussion.ui
  (:require [inside-out.forms :as forms]
            [sb.authorize :as az]
            [sb.app.discussion.data :as data]
            [sb.app.field.ui :as field.ui]
            [sb.app.form.ui :as form.ui]
            [sb.app.views.ui :as ui]
            [sb.i18n :refer [t]]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [sb.util :as u]
            [yawn.hooks :as h]))

(defn post-level [post]
  (dec (count (u/iterate-some :post/parent post))))

(declare show-posts)

(ui/defview show-post [post]
  ^{:key (:entity/id post)}
  (let [level (post-level post)
        author (:entity/created-by post)]
    [:div
     [:div.flex.gap-2.py-2.px-1
      [:a.flex-none {:href (routing/entity-path (or @(az/membership author (:entity/parent (u/auto-reduce :post/parent post)))
                                                    @author)
                                                'ui/show)}
       [ui/avatar {:size 12} author]]
      [:div.flex-v
       [:div.flex.gap-2.items-end
        [:div.font-bold (:account/display-name author)]
        [:div.text-sm.text-gray-500.flex-grow (ui/small-timestamp (:entity/created-at post))]]
       [:div
        (field.ui/show-prose
         (:post/text post))]]]
     (when (= 1 level)
       (let [!expanded (h/use-state false)
             nreplies (count (:post/_parent post))]
         [:div.ml-8
          (if @!expanded
            [show-posts post]
            [:span.icon-gray
             {:on-click #(reset! !expanded true)}
             (if (= 0 nreplies)
               (t :tr/reply)
               (str nreplies " " (t :tr/replies)))])]))]))

(ui/defview post-form [parent-id]
  (forms/with-form [!post {:post/parent parent-id
                           :post/text {:prose/format :prose.format/markdown
                                       :prose/string (?string :init "")}}]
    [:form
     {:class "flex-v gap-4 p-6 bg-back relative text-sm"
      :on-submit (fn [^js e]
                   (.preventDefault e)
                   (ui/with-submission [result (data/new-post! {:post @!post})
                                        :form !post]
                     (reset! ?string "")))}
     [field.ui/text-field ?string {:field/can-edit? true
                                   :field/multi-line? true}]
     [form.ui/submit-form !post (t :tr/post)]]))

(ui/defview show-posts [parent]
  [:<>
   (cond-> (into [:<>]
                 (map show-post)
                 (sort-by :entity/created-at (:post/_parent parent)))
     (< (post-level parent) 2)
     (conj [post-form (sch/wrap-id parent)]))])
