(ns server.dev-server
  (:require [server.main :as main]
            [applied-science.js-interop :as j]))

(def dev-port 3000)
(defonce dev-server (atom nil))

(defn dev-stop []
  (some-> @dev-server (j/call :close))
  (reset! dev-server nil))

(defn ^:dev/after-load dev-start []
  (dev-stop)
  (reset! dev-server
          (j/call main/app :listen (doto 3000
                                     (->> (prn :started-server))))))



(defonce _ (dev-start))