(ns sb.client.sanitize
  (:require [applied-science.js-interop :as j]
            [yawn.hooks :as hooks]
            [yawn.view :as v]
#?(:cljs ["linkify-element" :as linkify-element]))
  #?(:cljs (:import (goog.html SafeHtml SafeUrl)
                    (goog.html.sanitizer.HtmlSanitizer Builder))))

#?(:cljs
   (def !Sanitizer (delay
                     (-> (Builder.)
                         (.withCustomNetworkRequestUrlPolicy SafeUrl.sanitize)
                         (.allowCssStyles)
                         (.build)))))


(v/defview safe-html [html]
  (let [!el (hooks/use-ref)]
    (hooks/use-effect
      (fn []
        (when-let [^js el @!el]
          (some-> (.-firstChild el)
                  (j/call :remove))
          (doto el
            (.appendChild (or (some->> html
                                       (.sanitizeToDomNode ^js @!Sanitizer)
                                       linkify-element)
                              (js/document.createElement "div"))))))
      [@!el html])
    [:div.overflow-hidden
     {:ref                     !el
      :dangerouslySetInnerHTML {:__html ""}}]))