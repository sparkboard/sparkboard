(ns sparkboard.app.social-feed.data
  (:require [sparkboard.schema :as sch :refer [s- ?]]))

(sch/register!
  {:social-feed.twitter/hashtags (merge sch/many
                                        {s- [:set :string]})
   :social-feed.twitter/mentions (merge sch/many
                                        {s- [:set :string]})
   :social-feed.twitter/profiles (merge sch/many
                                        {s- [:set :string]})
   :social/feed                  {:doc "Settings for a live feed of social media related to an entity"
                                  s-   [:map {:closed true}
                                        (? :social-feed.twitter/hashtags)
                                        (? :social-feed.twitter/profiles)
                                        (? :social-feed.twitter/mentions)]}})