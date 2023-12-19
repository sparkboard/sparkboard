(ns sparkboard.app.views.layouts)

(defn two-col [left & right]
  [:div.grid.grid-cols-1.md:grid-cols-2.h-screen
   [:div.flex-v.justify-center.order-last.md:order-first.bg-secondary.relative
    left]
   (into [:div.flex-v.shadow-sm.relative.text-center.gap-6.justify-center]
         right)])