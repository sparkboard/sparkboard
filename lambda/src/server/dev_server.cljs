(ns server.dev-server
  (:require [server.main :as main]
            [server.slack :as slack]
            [applied-science.js-interop :as j]))

(def ENABLED? true)

(def dev-port 3000)
(defonce dev-server (atom nil))

(defn dev-stop []
  (some-> @dev-server (j/call :close))
  (reset! dev-server nil))

(defn dev-start []
  (dev-stop)
  (reset! dev-server
          (j/call main/app :listen (doto 3000
                                     (->> (prn :started-server))))))

(defn ^:dev/after-load auto-start []
  (if ENABLED?
    (dev-start)
    (dev-stop)))

(defonce _ (auto-start))
