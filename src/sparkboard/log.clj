(ns sparkboard.log
  (:require [clojure.string :as str]
            [sparkboard.server.env :as env]
            [taoensso.timbre :as log]
            [timbre-ns-pattern-level :as timbre-patterns]))

(def log-levels
  (or (env/config :dev.logging/levels)
      (case (env/config :env)
        "staging" '{:all :info
                    sparkboard.slack.oauth :trace
                    sparkboard.server.core :trace}
        "prod" {:all :warn}
        '{:all :info})))

(-> {:middleware [(timbre-patterns/middleware
                   (reduce-kv
                    (fn [m k v] (assoc m (cond-> k (symbol? k) (str)) v))
                    {} log-levels))]
     :level :trace}
    ;; add :dev.logging/tap? to .local.config.edn to use tap>
    (cond-> (env/config :dev.logging/tap?)
            (assoc :appenders
                   {:tap> {:min-level nil
                           :enabled? true
                           :output-fn (fn [{:keys [?ns-str ?line vargs]}]
                                        (into [(-> ?ns-str
                                                   (str/replace "sparkboard." "")
                                                   (str ":" ?line)
                                                   (symbol))] vargs))
                           :fn (comp tap> force :output_)}}))
    (log/merge-config!))