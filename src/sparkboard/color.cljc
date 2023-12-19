(ns sparkboard.color)

(def invalid-border-color "red")
(def invalid-text-color "red")
(def invalid-bg-color "light-pink")

(defn contrasting-text-color [bg-color]
  (if bg-color
    (try (let [[r g b] (if (= \# (first bg-color))
                         (let [bg-color (if (= 4 (count bg-color))
                                          (str bg-color (subs bg-color 1))
                                          bg-color)]
                           [(js/parseInt (subs bg-color 1 3) 16)
                            (js/parseInt (subs bg-color 3 5) 16)
                            (js/parseInt (subs bg-color 5 7) 16)])
                         (re-seq #"\d+" bg-color))
               luminance (/ (+ (* r 0.299)
                               (* g 0.587)
                               (* b 0.114))
                            255)]
           (if (> luminance 0.5)
             "#000000"
             "#ffffff"))
         (catch js/Error e "#000000"))
    "#000000"))