(ns sb.app.notification.ui
  (:require [re-db.api :as db]
            [sb.app.field.ui :as field.ui]
            [sb.app.membership.ui :as member.ui]
            [sb.app.notification.data :as data]
            [sb.schema :as sch]
            [sb.app.views.ui :as ui]
            [sb.i18n :as i :refer [t]]
            [sb.routing :as routing]
            [net.cgrand.xforms :as xf]))


(ui/defview new-post [{{author :entity/created-by :post/keys [text parent]} :notification/subject}]
  [:div.flex.gap-2
   [:div.flex-none
    [ui/avatar {:size 12} author]]
   [:div.flex-v.min-w-0
    [:div.flex.gap-1.items-center
     [:div.font-bold.whitespace-nowrap (:account/display-name author)]
     [:div.text-gray-500.text-sm.truncate
      (if (= :post (:entity/kind parent))
        [:<>
         (t :tr/replied-to)
         " "
         [field.ui/truncated-prose (:post/text parent)]]
        (t :tr/wrote))]
     #_[:div.text-sm.text-gray-500.flex-grow (ui/small-timestamp (:entity/created-at post))]]
    [field.ui/truncated-prose text]]])

(ui/defview new-member [{{:keys [membership/member]} :notification/subject
                         :keys [notification/profile]}]
  [:div.flex.items-center.gap-2
   [ui/avatar {:size 12} member]
   [:div.flex-v.gap-1
    [:div
     (:account/display-name member)
     [:span.text-gray-500.text-sm
      " "
      (t :tr/joined)]]
    [member.ui/tags :small profile]]])

(ui/defview new-invitation [{{:keys [membership/member]} :notification/subject}]
  (if (sch/id= member (db/get :env/config :account))
    (t :tr/you-are-invited-to-join)
    "unreachable"))

(ui/defview show [notification]
  [:div.flex.gap-2.items-center
   [:div.bg-focus-accent.w-2.h-2.rounded.flex-none
    {:class (when (:notification/viewed? notification)
              "opacity-0")}]
   [:div.min-w-0
    ((case (:notification/type notification)
       :notification.type/new-post new-post
       :notification.type/new-member new-member
       :notification.type/new-invitation new-invitation
       (comp ui/pprinted deref))
     notification)]])

(ui/defview show-all []
  (if-let [all (not-empty (data/all nil))]
    (into [:div.overflow-auto.flex-v.gap-6.max-w-prose {:class "max-h-[90vh]"}]
          (comp (xf/sort-by #(.getTime (:entity/created-at %)) >)
                (partition-by (comp ui/small-datestamp :entity/created-at))

                (map (fn [notifications-on-day]
                       (into [:div.flex-v
                              [:div.mx-auto.sticky.top-1.bg-white.z-10.p-1.rounded-sm
                               (ui/small-datestamp (:entity/created-at (first notifications-on-day)))]]
                             (comp (partition-by data/get-context)
                                   (map (fn [notifications-about-project]
                                          (let [project (data/get-context (peek notifications-about-project))]
                                            [:a.block.hover:bg-gray-100.p-3.pl-2
                                             {:href (routing/entity-path project 'ui/show)}
                                             [:div.font-semibold.text-gray-600.truncate.mb-1.ml-4
                                              (:entity/title project)]
                                             (into [:div.flex-v.gap-2] (map show) notifications-about-project)]))))
                             notifications-on-day))))
          all)
    [:div.p-2
     {:style {:width 360}}
     (t :tr/no-notifications)]))
