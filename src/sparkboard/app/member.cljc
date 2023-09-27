(ns sparkboard.app.member
  (:require #?(:clj [java-time.api :as time])
            #?(:clj [sparkboard.server.datalevin :as dl])
            [sparkboard.i18n :refer [tr]]
            [sparkboard.ui :as ui]
            [sparkboard.websockets :as ws]
            [sparkboard.entity :as entity]
            [re-db.api :as db])
  #?(:clj (:import [java.util Date])))

#?(:clj
   (defn db:read
     {:endpoint {:query ["/m/" ['uuid :member-id]]}}
     [params]
     (dissoc (db/pull `[:*
                        {:member/tags [:*]}
                        {:member/account [~@entity/fields
                                          :account/display-name]}]
                      [:entity/id (:member-id params)])
             :member/password)))

(ui/defview read
  {:view/target :modal
   :endpoint {:view ["/m/" ['uuid :member-id]]}}
  [params]
  (let [{:as          member
         :member/keys [tags
                       ad-hoc-tags
                       account]} (ws/use-query! [`db:read params])
        {:keys [:account/display-name
                :image/avatar]} account]
    [:div
     [:h1 display-name]
     ;; avatar
     ;; fields
     (when-let [tags (seq (concat tags ad-hoc-tags))]
       [:section [:h3 (tr :tr/tags)]
        (into [:ul]
              (map (fn [{:tag/keys [label background-color]}]
                     [:li {:style (when background-color {:background-color background-color})} label]))
              tags)])
     (when avatar [:img {:src (ui/asset-src avatar :card)}])]))

#?(:clj
   (defn membership-id [entity-id account-id]
     (dl/entid [:member/entity+account [(dl/resolve-id entity-id) (dl/resolve-id account-id)]])))

#?(:clj
   (defn member:read-and-log!
     ([entity-id account-id]
      (when-let [id (and account-id (membership-id entity-id account-id))]
        (member:read-and-log! {:member id})))
     ([params]
      (when-let [member (dl/pull '[:db/id
                                   :member/last-visited]
                                 (dl/resolve-id (:member params)))]
        (db/transact! [[:db/add (:db/id member) :member/last-visited
                        (-> (time/offset-date-time)
                            time/instant
                            Date/from)]])))))