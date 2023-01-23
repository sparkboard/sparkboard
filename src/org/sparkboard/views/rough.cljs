(ns org.sparkboard.views.rough
  (:require [applied-science.js-interop :as j]
            [yawn.convert :as convert]
            ["wired-elements"]))

(defn js-rename [obj from to]
  (let [v (j/get obj from)]
    (js-delete obj from)
    (j/!set obj to v)))

(defn convert-props [props]
  (-> (convert/interpret-props convert/defaults props)
      (js-rename "className" "class")))

(defn merge-props [{:as p1 class1 :class style1 :style}
                   {:as p2 class2 :class style2 :style}]
  (-> (merge p1 p2)
      (assoc :style (merge style1 style2)
             :class (str class1 " " class2))))

(defn with-props [props children]
  (let [[props2 children] (if (map? (first children))
                            [(first children) (rest children)]
                            [{} children])]
    (into [(convert-props (merge-props props props2))] children)))

(defn button [& args] (convert/x (into [:wired-button] (with-props {} args))))
(defn card [& args] (convert/x (into [:wired-card] (with-props {} args))))
(defn checkbox [& args] (convert/x (into [:wired-checkbox] (with-props {} args))))
(defn combo [& args] (convert/x (into [:wired-combo] (with-props {} args))))
(defn dialog [& args] (convert/x (into [:wired-dialog] (with-props {} args))))
(defn divider [& args] (convert/x (into [:wired-divider] (with-props {} args))))
(defn fab [& args] (convert/x (into [:wired-fab] (with-props {} args))))
(defn icon-button [& args] (convert/x (into [:wired-icon-button] (with-props {} args))))
(defn image [& args] (convert/x (into [:wired-image] (with-props {} args))))
(defn input [& args] (convert/x (into [:wired-input] (with-props {} args))))
(defn item [& args] (convert/x (into [:wired-item] (with-props {} args))))
(defn link [& args] (convert/x (into [:wired-link] (with-props {} args))))
(defn listbox [& args] (convert/x (into [:wired-listbox] (with-props {} args))))
(defn progress [& args] (convert/x (into [:wired-progress] (with-props {} args))))
(defn radio [& args] (convert/x (into [:wired-radio] (with-props {} args))))
(defn radio-group [& args] (convert/x (into [:wired-radio-group] (with-props {} args))))
(defn search-input [& args] (convert/x (into [:wired-search-input] (with-props {} args))))
(defn slider [& args] (convert/x (into [:wired-slider] (with-props {} args))))
(defn spinner [& args] (convert/x (into [:wired-spinner] (with-props {} args))))
(defn tab [& args] (convert/x (into [:wired-tab] (with-props {} args))))
(defn tabs [& args] (convert/x (into [:wired-tabs] (with-props {} args))))
(defn textarea [& args] (convert/x (into [:wired-textarea] (with-props {} args))))
(defn toggle [& args] (convert/x (into [:wired-toggle] (with-props {} args))))
(defn video [& args] (convert/x (into [:wired-video] (with-props {} args))))


(defn grid [{:as props :keys [style]} & children]
  (convert/x (into [:div]
                   (with-props {:style (merge {:display "grid"
                                               :gap "1rem"
                                               :grid-template-columns "repeat(auto-fit, minmax(200px, 1fr))"}
                                              style)} children))))