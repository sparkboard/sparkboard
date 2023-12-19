(ns sparkboard.app.asset.ui
  (:require [sparkboard.query-params :as query-params]
            [sparkboard.app.asset.data]))

(def variants {:avatar {:op "bound" :width 200 :height 200}
               :card   {:op "bound" :width 600}
               :page   {:op "bound" :width 1200}})

(defn asset-src [asset variant]
  (when-let [id (:entity/id asset)]
    (str "/assets/" id
         (some-> (variants variant) query-params/query-string))))

(defn css-url [s] (str "url(" s ")"))