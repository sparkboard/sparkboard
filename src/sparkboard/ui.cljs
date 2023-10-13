(ns sparkboard.ui
  (:require ["@radix-ui/react-dropdown-menu" :as dm]
            ["prosemirror-keymap" :refer [keydownHandler]]
            ["markdown-it" :as md]
            ["linkify-element" :as linkify-element]
            ["react" :as react]
            [applied-science.js-interop :as j]
            [clojure.pprint :refer [pprint]]
            [clojure.string :as str]
            [inside-out.forms :as forms]
            [inside-out.macros]
            [promesa.core :as p]
            [re-db.api :as db]
            [re-db.react]
            [shadow.lazy :as lazy]
            [sparkboard.client.sanitize :as sanitize]
            [sparkboard.i18n :as i]
            [sparkboard.util :as u]
            [sparkboard.ui.radix :as radix]
            [sparkboard.websockets :as ws]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [sparkboard.routes :as routes]
            [sparkboard.query-params :as query-params]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.ui.icons :as icons]
            [shadow.cljs.modern :refer [defclass]])
  (:require-macros [sparkboard.ui :refer [defview with-submission]]))

(defonce ^js Markdown (md))

(defview show-markdown
  [source]
  (let [!ref (h/use-ref)]
    (h/use-effect (fn []
                    (when-let [el @!ref]
                      (-> el
                          (j/!set :innerHTML (.render Markdown (or source "")))
                          (linkify-element))))
                  [@!ref])
    (v/x [:div {:class                   "prose"
                :ref                     !ref
                :dangerouslySetInnerHTML #js{:__html ""}}])))

(def variants {:avatar {:op "bound" :width 200 :height 200}
               :card   {:op "bound" :width 600}
               :page   {:op "bound" :width 1200}})

(defn asset-src [asset variant]
  (when asset
    (str "/assets/" (:asset/id asset)
         (some-> (variants variant) query-params/query-string))))

(defn filtered [match-text]
  (comp
    (remove :entity/archived?)
    (filter (if match-text
              #(re-find (re-pattern (str "(?i)" match-text)) (:entity/title %))
              identity))))

(defn pprinted [x]
  [:pre.whitespace-pre-wrap (with-out-str (pprint x))])

(def safe-html sanitize/safe-html)

(defn show-prose [{:as m :prose/keys [format string]}]
  (when m
    (case format
      :prose.format/html [sanitize/safe-html string]
      :prose.format/markdown [show-markdown string])))

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

(def invalid-border-color "red")
(def invalid-text-color "red")
(def invalid-bg-color "light-pink")

(defn loading:spinner [& [class]]
  (let [class (or class "h-4 w-4 text-blue-600 ml-2")]
    (v/x
      [:div.flex.items-center.justify-left
       [:svg.animate-spin
        {:xmlns   "http://www.w3.org/2000/svg"
         :fill    "none"
         :viewBox "0 0 24 24"
         :class   class}
        [:circle.opacity-25 {:cx "12" :cy "12" :r "10" :stroke "currentColor" :stroke-width "4"}]
        [:path.opacity-75 {:fill "currentColor" :d "M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"}]]])))

(defview view-message [{:keys [type content]}]
  (case type
    :in-progress (loading:spinner " h-4 w-4 text-blue-600 ml-2")
    [:div
     {:style (case type
               (:error :invalid) {:color            invalid-text-color
                                  :background-color invalid-bg-color}
               nil)}
     content]))

(defn auto-submit-handler [?field]
  (fn [& _]
    (let [!form (->> (iterate forms/parent ?field)
                     (take-while identity)
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
  {:id          (field-id ?field)
   :value       (or @?field "")
   :on-change   (fn [e]
                  (js/console.log (.. e -target -checked))
                  ((forms/change-handler ?field) e))
   :on-blur     (compseq (forms/blur-handler ?field)
                         (auto-submit-handler ?field))
   :on-focus    (forms/focus-handler ?field)
   :on-key-down #(when (= "Enter" (.-key ^js %))
                   ((auto-submit-handler ?field) %))})

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
                              [icons/loading "w-4 h-4 text-txt/40"]))]
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
        el    (:el props text-field)]
    (el ?field (dissoc props :el))))

(defn filter-field [?field & [attrs]]
  (let [loading? (or (:loading? ?field) (:loading? attrs))]
    [:div.flex.relative.items-stretch
     [:input.form-text.pr-9 (v/props (text-props ?field)
                                     {:on-key-down #(when (= "Escape" (.-key ^js %))
                                                      (reset! ?field nil))})]
     [:div.absolute.top-0.right-0.bottom-0.flex.items-center.pr-2
      {:class "text-txt/40"}
      (cond loading? (icons/loading "w-4 h-4 rotate-3s")
            (seq @?field) [:div.contents.cursor-pointer
                           {:on-click #(reset! ?field nil)}
                           (icons/close "w-5 h-5")]
            :else (icons/search "w-5 h-5"))]]))

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
  (let [src            (asset-src @?field :card)
        loading?       (:loading? ?field)
        !selected-blob (h/use-state nil)
        thumbnail      (use-loaded-image src @!selected-blob)]
    [:div.flex.flex-col.gap-3.relative.shadow-sm.border.p-3.rounded-lg
     (when loading?
       [icons/loading "w-4 h-4 absolute top-0 right-0 text-txt/40 mx-2 my-3"])
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
        :on-change (fn [e]
                     (forms/touch! ?field)
                     (when-let [file (j/get-in e [:target :files 0])]
                       (reset! !selected-blob (js/URL.createObjectURL file))
                       (with-submission [asset (routes/POST :asset/upload (doto (js/FormData.)
                                                                            (.append "files" file)))
                                         :form ?field]
                                        (reset! ?field asset)
                                        ((auto-submit-handler ?field)))))}]]
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
  (let [!result         (h/use-state {:loading? true})
        !unmounted?     (h/use-ref false)
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

(defview show-match
  "Given a match, shows the view, loading bar, and/or error message.
   - adds :data to params when a :query is provided"
  [{:as match :match/keys [endpoints params route]}]
  [:Suspense {:fallback (loading:spinner "w-4 h-4 absolute top-2 right-2")}
   (prn (-> endpoints :view :endpoint/sym) (-> endpoints :view :endpoint/sym (@routes/!views)))
   (if-let [view (-> endpoints :view :endpoint/sym (@routes/!views))]
     (when view
       [view (assoc params :account-id (db/get :env/account :account-id))])
     (pr-str match))])

(defn use-debounced-value
  "Caches value for `wait` milliseconds after last change."
  [value wait]
  (let [!state    (h/use-state value)
        !mounted  (h/use-ref false)
        !timeout  (h/use-ref nil)
        !cooldown (h/use-ref false)
        cancel    #(some-> @!timeout js/clearTimeout)]
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

(def form-classes "flex flex-col gap-8 p-6 max-w-lg mx-auto bg-back relative")
(def primary-button :button.btn.btn-primary.px-6.py-3.self-start)

(v/defview submit-form [!form label]
  [primary-button {:type     "submit"
                   :disabled (not (forms/submittable? !form))}
   label])

(defview redirect [to]
  (h/use-effect #(routes/set-path! to)))

(defn initials [display-name]
  (let [words (str/split display-name #"\s+")]
    (str/upper-case
      (str/join "" (map first
                        (if (> (count words) 2)
                          [(first words) (last words)]
                          words))))))

(defn avatar [{:keys [src display-name size]
               :or   {size 6}}]
  (let [classes (v/classes ["rounded-full"
                            (str "h-" size)
                            (str "w-" size)])]
    (if src
      [:img {:src src :class classes}]
      [:div.bg-gray-200.text-gray-600.inline-flex.items-center.justify-center
       {:class classes}
       (initials display-name)])))

(defview auto-height-textarea [{:as props :keys [value]}]
  (let [!text-element (h/use-ref nil)
        !text-height  (h/use-state nil)]
    (h/use-effect
      (fn []
        (reset! !text-height
                (some-> @!text-element
                        (j/call :getBoundingClientRect)
                        (j/get :height))))
      [@!text-element value])
    [:<>
     [:div.bg-black {:class (:class props)
                     :style (merge (:style props)
                                   {:color "white"
                                    :visibility "hidden"
                                    :position   "absolute"
                                    :left       0
                                    :top        0})
                     :ref   !text-element}
      (:value props)
      (when (= \newline (last (:value props))) " ")]
     [:textarea
      (update props :style merge {:height (some-> @!text-height (+ 2))})]]))

(defn keydown-handler [bindings]
  (let [handler (keydownHandler (clj->js bindings))]
    (fn [e] (handler #js{} e))))

(defclass ErrorBoundary
          (extends react/Component)
          (constructor [this props] (super props))
          Object
          (componentDidCatch [this error info]
                             (js/console.error error)
                             (js/console.log (j/get info :componentStack)))
          (render [this]
                  (if-let [e (j/get-in this [:state :error])]
                    ((j/get-in this [:props :fallback]) e)
                    (j/get-in this [:props :children]))))

(j/!set ErrorBoundary "getDerivedStateFromError"
        (fn [error]
          (js/console.error error)
          #js {:error error}))

(v/defview error-boundary [fallback child]
  [:el ErrorBoundary {:fallback fallback} child])

(def startTransition react/startTransition)