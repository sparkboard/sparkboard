(ns sparkboard.views.layouts)

(defn two-col [left & right]
  [:div.grid.grid-cols-1.md:grid-cols-2.h-screen
   [:div.flex.flex-col.justify-center.order-last.md:order-first.bg-secondary
    left]
   (into [:div.flex.flex-col.shadow-sm.relative.text-center.gap-6.justify-center]
         right)])