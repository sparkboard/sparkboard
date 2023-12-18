(ns sparkboard.app.assets.data
  (:require #?(:clj [ring.util.response :as resp])
            #?(:clj [sparkboard.server.assets :as assets])
            [sparkboard.authorize :as az]
            [sparkboard.server.datalevin :as dl]
            [sparkboard.schema :as sch :refer [s- ?]]
            [sparkboard.util :as u]
            [malli.core :as m]))

(sch/register!
  {:asset/provider                         (sch/ref :one :asset.provider/as-map)
   :asset.provider/type                    {s- [:enum :asset.provider/s3]}
   :asset.provider.s3/endpoint+bucket-name {:db/tupleAttrs [:s3/endpoint :s3/bucket-name]
                                            :db/unique     :db.unique/identity}
   :asset.provider/as-map                  {s- [:map {:closed true}
                                                :asset.provider/type
                                                :s3/endpoint
                                                :s3/bucket-name
                                                :s3/bucket-host]}

   :s3/bucket-name                         sch/unique-id-str
   :s3/bucket-host                         sch/unique-id-str
   :s3/endpoint                            {s- :string}

   :asset/content-type                     {s- :string}
   :asset/size                             {s- 'number?}
   :asset/variants                         (sch/ref :many :asset.variant/as-map)

   :asset/link                             {s- :string}
   :asset/link-failed?                     {s- :boolean}

   :asset/as-map                           {s- [:map {:closed true}
                                                :entity/id
                                                :entity/kind
                                                (? :asset/variants)
                                                (? :asset/provider)
                                                (? :asset/link)
                                                (? :asset/content-type)
                                                (? :asset/size)
                                                (? :entity/created-by)
                                                (? :entity/created-at)]}


   :asset.variant/params                   {s- :string}
   :asset.variant/provider                 (sch/ref :one :asset.provider/as-map)

   :asset.variant/provider+params          (merge {:db/tupleAttrs [:asset.variant/provider
                                                                   :asset.variant/params]}
                                                  sch/unique-id)

   :asset.variant/as-map                   {s- [:map {:closed true}
                                                :asset.variant/params
                                                :asset.variant/provider]}


   :image/avatar                           (sch/ref :one :asset/as-map)
   :image/logo-large                       (sch/ref :one :asset/as-map)
   :image/footer                           (sch/ref :one :asset/as-map)
   :image/background                       (sch/ref :one :asset/as-map)
   :image/sub-header                       (sch/ref :one :asset/as-map)})

(comment

  (sch/install-malli-schemas!)
  (m/explain :asset/as-map {:src            ""
                            :entity/id      (random-uuid)
                            :entity/kind    :asset
                            :asset/provider :asset.provider/s3}))

#?(:clj
   (defn serve-asset
     {:endpoint         {:get "/assets/:asset-id"}
      :endpoint/public? true}
     [req {:keys [asset-id query-params]}]
     (if-let [asset (some-> (dl/entity asset-id)
                            (u/guard (complement :asset/link-failed?)))]
       (resp/redirect
         (or
           (assets/variant-link! asset query-params)
           (assets/asset-link asset))
         301)
       (resp/not-found "Asset not found"))))

#?(:clj
   (defn upload!
     {:endpoint {:post "/upload"}
      :prepare  [az/with-account-id!]}
     [req {:keys [account-id]}]
     (assets/upload! req {:account-id account-id})))