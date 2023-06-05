(ns sparkboard.views.ui
  (:require [applied-science.js-interop :as j]
            [clojure.pprint :refer [pprint]]
            [inside-out.forms :as forms]
            [inside-out.macros]
            [promesa.core :as p]
            [re-db.react]
            [shadow.lazy :as lazy]
            [sparkboard.client.sanitize :as sanitize]
            [sparkboard.util :as u]
            [sparkboard.websockets :as ws]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [sparkboard.routes :as routes]
            [sparkboard.query-params :as query-params])
  (:require-macros [sparkboard.views.ui :refer [defview tr with-submission]]))

(def variants {:logo {:op "bound" :width 200 :height 200}
               :card {:op "bound" :width 600}
               :page {:op "bound" :width 1200}})

(defn asset-src [asset variant]
  (when asset
    (str "/assets/" (:asset/id asset)
         (some-> (variants variant) query-params/query-string))))

(defn filtered [?pattern]
  (filter (if @?pattern
            #(re-find (re-pattern @?pattern) (:entity/title %))
            identity)))

(defn pprinted [x]
  [:pre-wrap (with-out-str (pprint x))])

(def safe-html sanitize/safe-html)

(defn icon:arrow-back [& [class-name]]
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :viewBox "0 0 20 20"
         :fill "currentColor"
         :className class-name}
   [:g {:clipPath "url(#clip0_9_2121)"}
    [:path
     {:fillRule "evenodd"
      :d
      "M10 18a8 8 0 100-16 8 8 0 000 16zm3.25-7.25a.75.75 0 000-1.5H8.66l2.1-1.95a.75.75 0 10-1.02-1.1l-3.5 3.25a.75.75 0 000 1.1l3.5 3.25a.75.75 0 001.02-1.1l-2.1-1.95h4.59z"
      :clipRule "evenodd"}]]
   [:defs [:clipPath#clip0_9_2121 [:path {:d "M0 0h20v20H0z"}]]]])

(defn icon:settings [& [class-name]]
  (v/x
    [:svg {:class (or class-name "w-6 h-6") :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 24 24" :fill "currentColor"}
     [:path {:fillRule "evenodd" :d "M11.828 2.25c-.916 0-1.699.663-1.85 1.567l-.091.549a.798.798 0 01-.517.608 7.45 7.45 0 00-.478.198.798.798 0 01-.796-.064l-.453-.324a1.875 1.875 0 00-2.416.2l-.243.243a1.875 1.875 0 00-.2 2.416l.324.453a.798.798 0 01.064.796 7.448 7.448 0 00-.198.478.798.798 0 01-.608.517l-.55.092a1.875 1.875 0 00-1.566 1.849v.344c0 .916.663 1.699 1.567 1.85l.549.091c.281.047.508.25.608.517.06.162.127.321.198.478a.798.798 0 01-.064.796l-.324.453a1.875 1.875 0 00.2 2.416l.243.243c.648.648 1.67.733 2.416.2l.453-.324a.798.798 0 01.796-.064c.157.071.316.137.478.198.267.1.47.327.517.608l.092.55c.15.903.932 1.566 1.849 1.566h.344c.916 0 1.699-.663 1.85-1.567l.091-.549a.798.798 0 01.517-.608 7.52 7.52 0 00.478-.198.798.798 0 01.796.064l.453.324a1.875 1.875 0 002.416-.2l.243-.243c.648-.648.733-1.67.2-2.416l-.324-.453a.798.798 0 01-.064-.796c.071-.157.137-.316.198-.478.1-.267.327-.47.608-.517l.55-.091a1.875 1.875 0 001.566-1.85v-.344c0-.916-.663-1.699-1.567-1.85l-.549-.091a.798.798 0 01-.608-.517 7.507 7.507 0 00-.198-.478.798.798 0 01.064-.796l.324-.453a1.875 1.875 0 00-.2-2.416l-.243-.243a1.875 1.875 0 00-2.416-.2l-.453.324a.798.798 0 01-.796.064 7.462 7.462 0 00-.478-.198.798.798 0 01-.517-.608l-.091-.55a1.875 1.875 0 00-1.85-1.566h-.344zM12 15.75a3.75 3.75 0 100-7.5 3.75 3.75 0 000 7.5z" :clipRule "evenodd"}]]))

(defn icon:loading [& [class]]
  (v/x 
   [:svg {:xmlns   "http://www.w3.org/2000/svg"
          :viewBox "0 0 20 20"
          :fill    "currentColor"
          :class   ["rotate-3s" class]}
    [:path
     {:fillRule "evenodd"
      :d        "M15.312 11.424a5.5 5.5 0 01-9.201 2.466l-.312-.311h2.433a.75.75 0 000-1.5H3.989a.75.75 0 00-.75.75v4.242a.75.75 0 001.5 0v-2.43l.31.31a7 7 0 0011.712-3.138.75.75 0 00-1.449-.39zm1.23-3.723a.75.75 0 00.219-.53V2.929a.75.75 0 00-1.5 0V5.36l-.31-.31A7 7 0 003.239 8.188a.75.75 0 101.448.389A5.5 5.5 0 0113.89 6.11l.311.31h-2.432a.75.75 0 000 1.5h4.243a.75.75 0 00.53-.219z"
      :clipRule "evenodd"}]]))

(defn icon:search []
  (v/x [:svg.pointer-events-none.h-6.w-6.fill-slate-400
        {:xmlns "http://www.w3.org/2000/svg"}
        [:path {:d "M20.47 21.53a.75.75 0 1 0 1.06-1.06l-1.06 1.06Zm-9.97-4.28a6.75 6.75 0 0 1-6.75-6.75h-1.5a8.25 8.25 0 0 0 8.25 8.25v-1.5ZM3.75 10.5a6.75 6.75 0 0 1 6.75-6.75v-1.5a8.25 8.25 0 0 0-8.25 8.25h1.5Zm6.75-6.75a6.75 6.75 0 0 1 6.75 6.75h1.5a8.25 8.25 0 0 0-8.25-8.25v1.5Zm11.03 16.72-5.196-5.197-1.061 1.06 5.197 5.197 1.06-1.06Zm-4.28-9.97c0 1.864-.755 3.55-1.977 4.773l1.06 1.06A8.226 8.226 0 0 0 18.75 10.5h-1.5Zm-1.977 4.773A6.727 6.727 0 0 1 10.5 17.25v1.5a8.226 8.226 0 0 0 5.834-2.416l-1.061-1.061Z"}]]))

(defn show-prose [{:as m :prose/keys [format string]}]
  (when m
    (case format
      :prose.format/html [sanitize/safe-html string]
      :prose.format/markdown string)))

(defn css-url [s] (str "url(" s ")"))

(def logo-url "/images/logo-2023.png")

(defn logo [classes]
  [:svg {:class       classes
         :viewBox     "0 0 551 552"
         :version     "1.1"
         :xmlns       "http://www.w3.org/2000/svg"
         :xmlns-xlink "http://www.w3.org/1999/xlink"
         :xml-space   "preserve"
         :fill        "currentColor"
         :style       {:fill-rule         "evenodd"
                       :clip-rule         "evenodd"
                       :stroke-linejoin   "round"
                       :stroke-miterlimit 2}}
   [:path {:d "M282,0.5L550.5,0.5L550.5,273.5L462.5,273.5L462.5,313L539.5,313L539.5,551.5L308,551.5L308,507.5L252,507.5L252,548.5L0.5,548.5L0.5,313L105,313L105,279L6.5,279L6.5,6.5L234.5,6.5L234.5,77L282,77L282,0.5ZM283,1.5L283,78L233.5,78L233.5,7.5L7.5,7.5L7.5,278L106,278L106,314L1.5,314L1.5,547.5L251,547.5L251,506.5L309,506.5L309,550.5L538.5,550.5L538.5,314L461.5,314L461.5,272.5L549.5,272.5L549.5,1.5L283,1.5ZM305,24L527,24L527,249L461.5,249L461.5,202.5L439,202.5L439,249L380,249L380,176.5L305,176.5L305,100L353,100L353,78L305,78L305,24ZM306,25L306,77L354,77L354,101L306,101L306,175.5L381,175.5L381,248L438,248L438,201.5L462.5,201.5L462.5,248L526,248L526,25L306,25ZM30.5,30L210.5,30L210.5,78L173,78L173,100L210.5,100L210.5,144.5L233.5,144.5L233.5,100L283,100L283,176.5L188.5,176.5L188.5,278L218,278L218,206.5L283,206.5L283,272.5L349,272.5L349,302.5L380,302.5L380,272.5L439,272.5L439,314L309,314L309,484L251,484L251,314L136,314L136,278L159,278L159,255.5L136,255.5L136,221.5L106,221.5L106,255.5L30.5,255.5L30.5,30ZM31.5,31L31.5,254.5L105,254.5L105,220.5L137,220.5L137,254.5L160,254.5L160,279L137,279L137,313L252,313L252,483L308,483L308,313L438,313L438,273.5L381,273.5L381,303.5L348,303.5L348,273.5L282,273.5L282,207.5L219,207.5L219,279L187.5,279L187.5,175.5L282,175.5L282,101L234.5,101L234.5,145.5L209.5,145.5L209.5,101L172,101L172,77L209.5,77L209.5,31L31.5,31ZM305,206.5L349,206.5L349,249L305,249L305,206.5ZM306,207.5L306,248L348,248L348,207.5L306,207.5ZM328.5,333.5L439,333.5L439,400.5L461.5,400.5L461.5,333.5L519.5,333.5L519.5,532L328.5,532L328.5,506.5L361.5,506.5L361.5,484L328.5,484L328.5,333.5ZM329.5,334.5L329.5,483L362.5,483L362.5,507.5L329.5,507.5L329.5,531L518.5,531L518.5,334.5L462.5,334.5L462.5,401.5L438,401.5L438,334.5L329.5,334.5ZM32.5,345L106,345L106,374L136,374L136,345L220.5,345L220.5,484L181,484L181,506.5L220.5,506.5L220.5,517.5L32.5,517.5L32.5,345ZM33.5,346L33.5,516.5L219.5,516.5L219.5,507.5L180,507.5L180,483L219.5,483L219.5,346L137,346L137,375L105,375L105,346L33.5,346Z"}]
   [:path {:d "M283,1.5L549.5,1.5L549.5,272.5L461.5,272.5L461.5,314L538.5,314L538.5,550.5L309,550.5L309,506.5L251,506.5L251,547.5L1.5,547.5L1.5,314L106,314L106,278L7.5,278L7.5,7.5L233.5,7.5L233.5,78L283,78L283,1.5ZM32.5,345L32.5,517.5L220.5,517.5L220.5,506.5L181,506.5L181,484L220.5,484L220.5,345L136,345L136,374L106,374L106,345L32.5,345ZM30.5,30L30.5,255.5L106,255.5L106,221.5L136,221.5L136,255.5L159,255.5L159,278L136,278L136,314L251,314L251,484L309,484L309,314L439,314L439,272.5L380,272.5L380,302.5L349,302.5L349,272.5L283,272.5L283,206.5L218,206.5L218,278L188.5,278L188.5,176.5L283,176.5L283,100L233.5,100L233.5,144.5L210.5,144.5L210.5,100L173,100L173,78L210.5,78L210.5,30L30.5,30ZM305,206.5L305,249L349,249L349,206.5L305,206.5ZM305,24L305,78L353,78L353,100L305,100L305,176.5L380,176.5L380,249L439,249L439,202.5L461.5,202.5L461.5,249L527,249L527,24L305,24ZM328.5,333.5L328.5,484L361.5,484L361.5,506.5L328.5,506.5L328.5,532L519.5,532L519.5,333.5L461.5,333.5L461.5,400.5L439,400.5L439,333.5L328.5,333.5Z"}]])

(defn icon:checkmark [& [classes]]
  [:svg {:class classes :viewBox "0 0 20 20" :fill "currentColor" :aria-hidden "true"}
   [:path {:fill-rule "evenodd" :d "M16.704 4.153a.75.75 0 01.143 1.052l-8 10.5a.75.75 0 01-1.127.075l-4.5-4.5a.75.75 0 011.06-1.06l3.894 3.893 7.48-9.817a.75.75 0 011.05-.143z" :clip-rule "evenodd"}]])

(def invalid-border-color "red")
(def invalid-text-color "red")
(def invalid-bg-color "light-pink")

(defn loader [& [class]]
  (v/x
    [:div.flex.items-center.justify-left
     [:svg.animate-spin
      {:xmlns   "http://www.w3.org/2000/svg"
       :fill    "none"
       :viewBox "0 0 24 24"
       :class   class}
      [:circle.opacity-25 {:cx "12" :cy "12" :r "10" :stroke "currentColor" :stroke-width "4"}]
      [:path.opacity-75 {:fill "currentColor" :d "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"}]]]))

(defview view-message [{:keys [type content]}]
  (case type
    :in-progress (loader " h-4 w-4 text-blue-600 ml-2")
    [:div
     {:style (case type
               (:error :invalid) {:color            invalid-text-color
                                  :background-color invalid-bg-color}
               nil)}
     content]))

(defn auto-submit-handler [?field]
  (fn [& _]
    (let [!form  (->> (iterate forms/parent ?field)
                      ( take-while identity)
                      last)]
      (when-let [submit! (:auto-submit !form)]
        (forms/watch-promise ?field 
                             (u/p-when (forms/valid?+ !form)
                                       (submit! @!form)))))))

(defview input-label [props content]
  [:label.block.text-oreground-muted.text-sm.font-medium
   (v/props props)
   content])

(defn field-id [?field]
  (str "field-" (goog/getUid ?field)))

(defn pass-props [props] (dissoc props :multi-line :postfix :wrapper-class))

(defn compseq [& fs]
  (fn [& args]
    (doseq [f fs] (apply f args))))

(defn text-props [?field]
  {:id        (field-id ?field)
   :value     (or @?field "")
   :on-change (fn [e]
                (js/console.log (.. e -target -checked))
                ((forms/change-handler ?field) e))
   :on-blur   (compseq (forms/blur-handler ?field)
                       (auto-submit-handler ?field))
   :on-focus  (forms/focus-handler ?field)})

(defn show-field-messages [?field]
  (when-let [messages (seq (forms/visible-messages ?field))]
    (v/x (into [:div.gap-3.text-sm] (map view-message messages)))))

(defn show-label [?field & [props]]
  (when-let [label (or (:label props)
                       (:label (meta ?field)))]
    [input-label {:for (field-id ?field)} label]))

(defn show-postfix [?field props]
  (when-let [postfix (or (:postfix props) 
                         (:postfix (meta ?field))
                         (and (:loading? ?field)
                              [icon:loading "w-4 h-4 text-txt/40"]))]
    [:div.pointer-events-none.absolute.inset-y-0.right-0.flex.items-center.pr-3 postfix]))

(defn text-field
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field & [props]]
  (let [{:as props :keys [multi-line wrapper-class]} (merge props (:props (meta ?field)))]
    (v/x
      [:div.gap-2.flex.flex-col.relative
       {:class wrapper-class}
       (show-label ?field props)
       [:div.flex.relative
        [(if multi-line
           :textarea.form-text
           :input.form-text)
         (v/props (text-props ?field)
                  (pass-props props)
                  {:class [
                           (if (:invalid (forms/types (forms/visible-messages ?field)))
                             "outline-invalid"
                             "outline-default"

                             )]})]
        (show-postfix ?field props)]
       (show-field-messages ?field)])))

(defn prose-props [?field]
  {:value     (or (:prose/string @?field) "")
   :on-change (fn [e]
                (reset! ?field
                        (when-let [value (u/guard (.. ^js e -target -value) seq)]
                          {:prose/format :prose.format/markdown
                           :prose/string value})))})

(defn prose-field [?field & [props]]
  (text-field ?field (v/merge-props props (prose-props ?field))))

(defview checkbox-field
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field attrs]
  (let [messages (forms/visible-messages ?field)]
    [:<>
     [:input.h-5.w-5.rounded.border-gray-300.text-primary.focus:outline-primary
      (v/props {:type      "checkbox"
                :on-blur   (forms/blur-handler ?field)
                :on-focus  (forms/focus-handler ?field)
                :on-change (compseq #(reset! ?field (.. ^js % -target -checked))
                                    (auto-submit-handler ?field))
                :checked   (or @?field false)
                :class     (if (:invalid (forms/types messages))
                             "outline-default"
                             "outline-invalid")})]
     (when (seq messages)
       (into [:div.mt-1] (map view-message) messages))]))

(defn show-field [?field & [props]]
  (let [props (v/merge-props (:props (meta ?field)) props)
        el (:el props text-field)]
    (el ?field (dissoc props :el))))

(defn filter-field [?field & [attrs]]
  (show-field ?field (merge {:class         "pr-9"
                             :wrapper-class "flex-grow sm:flex-none"
                             :postfix       (if (:loading? attrs)
                                              (icon:loading "w-4 h-4 rotate-3s text-txt/40")
                                              (icon:search))}
                            (dissoc attrs :loading? :error))))



(defn error-view [{:keys [error]}]
  (when error
    [:div.px-body.my-4
     [:div.text-destructive.border-2.border-destructive.rounded.shadow.p-4
      (str error)]]))

(defn loading-bar [& [class]]
  [:div.relative
   {:class class}
   [:div.loading-bar]])

(defn upload-icon [class]
  [:svg {:class class :xmlns "http://www.w3.org/2000/svg" :viewBox "0 0 20 20" :fill "currentColor"}
   [:path {:d "M9.25 13.25a.75.75 0 001.5 0V4.636l2.955 3.129a.75.75 0 001.09-1.03l-4.25-4.5a.75.75 0 00-1.09 0l-4.25 4.5a.75.75 0 101.09 1.03L9.25 4.636v8.614z"}]
   [:path {:d "M3.5 12.75a.75.75 0 00-1.5 0v2.5A2.75 2.75 0 004.75 18h10.5A2.75 2.75 0 0018 15.25v-2.5a.75.75 0 00-1.5 0v2.5c0 .69-.56 1.25-1.25 1.25H4.75c-.69 0-1.25-.56-1.25-1.25v-2.5z"}]])

(defn use-loaded-image [url fallback]
  (let [!loaded (h/use-state #{})]
    (h/use-effect (fn []
                    (when url
                      (let [^js img (doto (js/document.createElement "img")
                                      (j/assoc-in! [:style :display] "none")
                                      (js/document.body.appendChild))]
                        (j/assoc! img
                                  :onload #(do (swap! !loaded conj url)
                                               (.remove img))
                                  :src url)))) [url])
    (if (contains? @!loaded url)
      url
      fallback)))

(defview image-field [?field]
  (let [src (asset-src @?field :card)
        loading? (:loading? ?field)
        !selected-blob (h/use-state nil)
        thumbnail (use-loaded-image src @!selected-blob)]
    [:div.flex.flex-col.gap-3.relative.shadow-sm.border.p-3.rounded-lg
     (when loading?
           [icon:loading "w-4 h-4 absolute top-0 right-0 text-txt/40 mx-2 my-3"])
     (show-label ?field)
     [:label.block.w-32.h-32.relative.rounded.cursor-pointer.flex.items-center.justify-center
      (v/props {:class ["border-primary/20 bg-back"
                        "text-muted-txt hover:text-txt"]
                :for   (field-id ?field)}
               (when thumbnail
                 {:class "bg-contain bg-no-repeat bg-center shadow-inner"
                  :style {:background-image (css-url thumbnail)}}))
      
      (when-not thumbnail
        (upload-icon "w-6 h-6 m-auto"))
      
      [:input.hidden
       {:id        (field-id ?field)
        :type      "file"
        :accept    "image/webp, image/jpeg, image/gif, image/png, image/svg+xml"
        :on-change (compseq 
                    (fn [e]
                      (forms/touch! ?field)
                      (when-let [file (j/get-in e [:target :files 0])]
                        (reset! !selected-blob (js/URL.createObjectURL file))
                        (with-submission [asset (routes/POST :asset/upload (doto (js/FormData.)
                                                                             (.append "files" file)))
                                          :form ?field]
                          (reset! ?field asset)
                          ((auto-submit-handler ?field))))))}]]
     (show-field-messages ?field)]))

(def email-schema [:re #"^[^@]+@[^@]+$"])

(comment
  (defn malli-validator [schema]
    (fn [v _]
      (vd/humanized schema v))))



(defn merge-async
  "Accepts a collection of {:loading?, :error, :value} maps, returns a single map:
   - :loading? if any result is loading
   - :error is first error found
   - :value is a vector of values"
  [results]
  (if (map? results)
    results
    {:error    (first (keep :error results))
     :loading? (boolean (seq (filter :loading? results)))
     :value    (mapv :value results)}))

(defview show-async-status
  "Given a map of {:loading?, :error}, shows a loading bar and/or error message"
  [result]
  [:<>
   (when (:loading? result) [loading-bar "bg-blue-100 h-1"])
   (when (:error result) [error-view result])])

(defn use-promise
  "Returns a {:loading?, :error, :value} map for a promise (which should be memoized)"
  [promise]
  (let [!result (h/use-state {:loading? true})
        !unmounted? (h/use-ref false)
        !latest-promise (h/use-ref promise)]
    (h/use-effect (constantly #(reset! !unmounted? true)))
    (h/use-effect (fn []
                    (when-not @!unmounted?
                      (reset! !latest-promise promise)
                      (-> (p/let [value promise]
                            (when (identical? promise @!latest-promise)
                              (reset! !result {:value value})))
                          (p/catch (fn [e]
                                     (when (identical? promise @!latest-promise)
                                       (reset! !result {:error (ex-message e)})))))))
                  [promise])
    @!result))

(defn show-match
  "Given a match, shows the view, loading bar, and/or error message.
   - adds :data to params when a :query is provided"
  [{:keys [view query params]}]
  (let [view-result (use-promise
                      (h/use-memo #(cond (not (instance? lazy/Loadable view)) view
                                         (lazy/ready? view) @view
                                         :else (lazy/load view))
                                  [view]))
        query-result (when query
                       (ws/watch (:route params)))
        {:as                 result
         [view query params] :value} (-> [view-result query-result {:value params}]
                                         merge-async
                                         ws/use-cached-result)]
    [:<>
     [show-async-status result]
     (when view
       [view (assoc params :data query)])]))

(defn use-debounced-value
  "Caches value for `wait` milliseconds after last change."
  [value wait]
  (let [!state (h/use-state value)
        !mounted (h/use-ref false)
        !timeout (h/use-ref nil)
        !cooldown (h/use-ref false)
        cancel #(some-> @!timeout js/clearTimeout)]
    (h/use-effect
      (fn []
        (when @!mounted
          (if @!cooldown
            (do (cancel)
                (reset! !timeout
                        (js/setTimeout
                          #(do (reset! !cooldown false)
                               (reset! !state value))
                          wait)))
            (do
              (reset! !cooldown true)
              (reset! !state value)))))
      [value wait])
    (h/use-effect
      (fn []
        (reset! !mounted true)
        cancel))
    @!state))

(def email-validator (fn [v _]
                       (when v
                         (when-not (re-find #"^[^@]+@[^@]+$" v)
                           (tr :tr/invalid-email)))))


(defn ^:dev/after-load init-forms []
  #_(when k
      (let [validator (some-> schema/sb-schema (get k) :malli/schema malli-validator)]
        (cond-> (k field-meta)
                validator
                (update :validators conj validator))))
  (forms/set-global-meta!
    (tr
      {:account/email    {:el         text-field
                          :props      {:type        "email"
                                       :placeholder :tr/email}
                          :validators [email-validator]}
       :account/password {:el         text-field
                          :props      {:type        "password"
                                       :placeholder :tr/password}
                          :validators [(forms/min-length 8)]}}))
  )