(ns org.sparkboard.client.env
  #?(:clj (:require [org.sparkboard.server.env :as server-env]))
  #?(:cljs (:require-macros [org.sparkboard.client.env :refer [read-config]])))

#?(:clj
   (defmacro read-config []
     (let [config (server-env/read-config)]
       (-> config
           (select-keys [:firebase/app-config :sparkboard/jvm-root])
           (assoc :slack/app-id (-> config :slack :app-id))))))

#?(:cljs (def config (read-config)))
