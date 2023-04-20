(ns sparkboard.views.ui
  (:require [inside-out.macros]
            [re-db.react]
            [yawn.view :as v])
  #?(:cljs (:require-macros sparkboard.views.ui)))

(defmacro defview [name & args]
  (v/defview:impl
   {:wrap-expr (fn [expr] `(re-db.react/use-derefs ~expr))}
   name
   args))

(defmacro with-form [bindings & body]
  (inside-out.macros/with-form* &form &env {} bindings [`(v/x ~@body)]))

(def c:dark-button
  (v/classes ["inline-flex items-center justify-center "
              "font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none "
              "ring-offset-background bg-primary text-primary-foreground hover:bg-primary/90 "
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 "
              "rounded-md"]))

(def c:light-button
  (v/classes ["inline-flex items-center justify-center cursor-pointer"
              "font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none "
              "ring-offset-background border hover:border-primary/30 text-primary"
              "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 "
              "rounded-md px-3"]))


#?(:cljs
   (do
     (def a:dark-button
       (v/from-element :a
         {:class
          ["inline-flex items-center justify-center "
           "font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none "
           "ring-offset-background bg-primary text-primary-foreground hover:bg-primary/90 "
           "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 "
           "rounded-md px-3"]}))

     (def a:light-button
       (v/from-element :a
         {:class
          ["inline-flex items-center justify-center cursor-pointer"
           "font-medium transition-colors disabled:opacity-50 disabled:pointer-events-none "
           "ring-offset-background border hover:border-primary/30 text-primary"
           "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 "
           "rounded-md px-3"]}))))

(defn css-url [s] (str "url(" s ")"))