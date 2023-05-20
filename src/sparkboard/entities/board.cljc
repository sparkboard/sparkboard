(ns sparkboard.entities.board
  (:require #?(:clj [sparkboard.datalevin :as sd])
            [promesa.core :as p]
            [re-db.api :as db]
            [sparkboard.routes :as routes]
            [sparkboard.server.query :as query]
            [sparkboard.validate :as validate]
            [sparkboard.views.ui :as ui]))

(ui/defview new:view [{:as params :keys [route]}]
  (ui/with-form [!board {:entity/title ?title}]
    [:div
     [:h3 :tr/new-board]
     (ui/show-field ?title)
     [:button {:on-click #(p/let [res (routes/POST route @!board '[*])]
                            (when-not (:error res)
                              (routes/set-path! :org/read params)
                              ;; FIXME "Uncaught (in promise) DOMException: The operation was aborted."
                              ))}
      :tr/create]]))

(query/static new:post
  [req params board pull]
  (validate/assert board [:map {:closed true} :entity/title])
  ;; auth: user is admin of :board/org
  (db/transact!
    [(-> board
         (assoc :board/org [:entity/id (:entity/id params)])
         (sd/new-entity :by (:db/id (:account req))))])
  (db/pull pull))

(ui/defview register:view [{:as params :keys [route]}]
  (ui/with-form [!member {:member/name ?name :member/password ?pass}]
    [:div
     [:h3 :tr/register]
     (ui/show-field ?name)
     (ui/show-field ?pass)
     [:button {:on-click #(p/let [res (routes/POST route @!member)]
                            ;; TODO - how to determine POST success?
                            #_(when (http-ok? res)
                                (routes/set-path! [:board/read params])
                                res))}
      :tr/register]]))

(query/static register:post [ctx _ registration-map]
  ;; create membership
  )

(ui/defview read:view [{:as params board :data}]
  [:<>
   [:h1 (:entity/title board)]
   [:p (-> board :entity/domain :domain/name)]
   [:blockquote
    [ui/safe-html (-> board
                      :entity/description
                      :text-content/string)]]
   ;; TODO - tabs
   [:div.rough-tabs {:class "w-100"}
    [:div.rough-tab                                         ;; projects
     [:a {:href (routes/path-for :project/new params)} :tr/new-project]
     (into [:ul]
           (map (fn [proj]
                  [:li [:a {:href (routes/path-for :project/read {:project (:entity/id proj)})}
                        (:entity/title proj)]]))
           (:project/_board board))]
    [:div.rough-tab                                         ;; members
     [:a {:href (routes/path-for :board/register params)} :tr/new-member]
     (into [:ul]
           (map (fn [member]
                  [:li
                   [:a {:href (routes/path-for :member/read {:member (:entity/id member)})}
                    (:member/name member)]]))
           (:member/_board board))]
    [:div.rough-tab {:name "I18n"                           ;; FIXME any spaces in the tab name cause content to break; I suspect a bug in `with-props`. DAL 2023-01-25
                     :class "db"}
     [:ul                                                   ;; i18n stuff
      [:li "suggested locales:" (str (:entity/locale-suggestions board))]
      [:li "default locale:" (str (:i18n/default-locale board))]
      [:li "extra-translations:" (str (:i18n/locale-dicts board))]]]]])

(defn read:query [params]
  (db/pull '[:entity/id
             :entity/kind
             :entity/title
             :board/registration-open?
             {:project/_board [*]}
             {:board/org [:entity/id
                          :entity/kind
                          :entity/title]}
             {:member/_board [*]}
             {:entity/domain [:domain/name]}]
           [:entity/id (:board params)]))
