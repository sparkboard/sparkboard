(ns sparkboard.ui
  (:require ["@radix-ui/react-dropdown-menu" :as dm]
            ["prosemirror-keymap" :refer [keydownHandler]]
            ["markdown-it" :as md]
            ["linkify-element" :as linkify-element]
            ["react" :as react]
            [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [clojure.pprint]
            [clojure.set :as set]
            [clojure.string :as str]
            [inside-out.forms :as forms]
            [inside-out.macros]
            [promesa.core :as p]
            [re-db.api :as db]
            [re-db.react]
            [sparkboard.client.sanitize :as sanitize]
            [sparkboard.i18n :as i]
            [sparkboard.ui.radix :as radix]
            [sparkboard.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [sparkboard.routing :as routing]
            [sparkboard.query-params :as query-params]
            [sparkboard.i18n :refer [tr]]
            [sparkboard.ui.icons :as icons]
            [sparkboard.app.assets :as assets]
            [shadow.cljs.modern :refer [defclass]])
  (:require-macros [sparkboard.ui :refer [defview with-submission]]))

(defn dev? [] (= "dev" (db/get :env/config :env)))

(defn loading-bar [& [class]]
  [:div.relative
   {:class class}
   [:div.loading-bar]])

(defonce ^js Markdown (md))

(defview show-markdown
  [source]
  (let [!ref (h/use-ref)]
    (h/use-effect (fn []
                    (when-let [el @!ref]
                      (-> el
                          (j/!set :innerHTML (.render Markdown (or source "")))
                          (linkify-element))))
                  [@!ref source])
    (v/x [:div {:class                   "prose contents"
                :ref                     !ref
                :dangerouslySetInnerHTML #js{:__html ""}}])))

(def variants {:avatar {:op "bound" :width 200 :height 200}
               :card   {:op "bound" :width 600}
               :page   {:op "bound" :width 1200}})

(defn asset-src [asset variant]
  (when-let [id (:entity/id asset)]
    (str "/assets/" id
         (some-> (variants variant) query-params/query-string))))

(defn filtered [match-text]
  (comp
    (remove :entity/archived?)
    (filter (if match-text
              #(re-find (re-pattern (str "(?i)" match-text)) (:entity/title %))
              identity))))

(defn pprinted [x & _]
  [:pre.whitespace-pre-wrap (with-out-str (clojure.pprint/pprint x))])

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

(def input-label (v/from-element :label.block.font-bold.text-base))
(def input-wrapper (v/from-element :div.gap-2.flex-v.relative))

(defn field-id [?field]
  (str "field-" (goog/getUid ?field)))

(v/defview auto-size [props]
  (let [v!    (h/use-state "")
        props (merge props {:value     (:value props @v!)
                            :on-change (:on-change props
                                         #(reset! v! (j/get-in % [:target :value])))})]
    [:div.auto-size
     [:div.bg-black (select-keys props [:class :style])
      (str (:value props) " ")]
     [:textarea (assoc props :rows 1)]]))

(defn pass-props [props] (dissoc props
                                 :multi-line :postfix :wrapper-class
                                 :persisted-value :on-save :on-change-value
                                 :wrap :unwrap
                                 :inline?
                                 :can-edit?
                                 :label))

(defn compseq [& fs]
  (fn [& args]
    (doseq [f fs] (apply f args))))

(defn maybe-save-field [?field props value]
  (when-let [on-save (and (not= value (:persisted-value props))
                          (:on-save props))]
    (forms/try-submit+ ?field
      (on-save value))))

(defn show-label [?field & [label]]
  (when-let [label (u/some-or label (:label ?field))]
    [input-label {:class "text-label"
                  :for   (field-id ?field)} label]))

(defn common-props [?field
                    get-value
                    {:as   props
                     :keys [wrap
                            unwrap
                            on-save
                            on-change-value
                            on-change
                            persisted-value]
                     :or   {wrap identity unwrap identity}}]
  (cond-> {:id        (field-id ?field)
           :value     (unwrap @?field)
           :on-change (fn [e]
                        (let [new-value (wrap (get-value e))]
                          (reset! ?field new-value)
                          (when on-change-value
                            (pass-props (on-change-value new-value)))
                          (when on-change
                            (on-change e))))
           :on-blur   (fn [e]
                        (maybe-save-field ?field props @?field)
                        ((forms/blur-handler ?field) e))
           :on-focus  (forms/focus-handler ?field)}
          persisted-value
          (assoc :persisted-value (unwrap persisted-value))))

(defn color-field [?field props]
  (let [get-value (j/get-in [:target :value])]
    [:input.default-ring.rounded
     (-> (v/merge-props
           (pass-props props)
           (common-props ?field get-value props)
           {:on-blur (fn [e]
                       (reset! ?field (get-value e))
                       (maybe-save-field ?field props (get-value e)))
            :type    "color"})
         (update :value #(or % "#ffffff")))]))

(defview select-field [?field {:as props :keys [label options]}]
  [input-wrapper
   (show-label ?field label)
   [radix/select-menu (-> (common-props ?field identity (assoc props
                                                          :on-change #(maybe-save-field ?field props @?field)))
                          (set/rename-keys {:on-change :on-value-change})
                          (assoc :can-edit? (:can-edit? props)
                                 :options (->> options
                                               (map (fn [{:field-option/keys [label value color]}]
                                                      {:text  label
                                                       :value value}))
                                               doall)))]
   (when (:loading? ?field)
     [:div.loading-bar.absolute.bottom-0.left-0.right-0 {:class "h-[3px]"}])])

(defn show-field-messages [?field]
  (when-let [messages (seq (forms/visible-messages ?field))]
    (v/x (into [:div.gap-3.text-sm] (map view-message messages)))))

(defn show-postfix [?field props]
  (when-let [postfix (or (:postfix props)
                         (:postfix (meta ?field))
                         (and (some-> (:persisted-value props)
                                      (not= (:value props)))
                              [icons/pencil-outline "w-4 h-4 text-txt/40"]))]
    [:div.pointer-events-none.absolute.inset-y-0.right-0.top-0.bottom-0.flex.items-center.p-2 postfix]))

(defn keydown-handler [bindings]
  (let [handler (keydownHandler (reduce-kv (fn [out k f]
                                             (j/!set out (name k) (fn [_ _ _] (f js/window.event)))) #js{} bindings))]
    (fn [e] (handler #js{} e))))

(defn text-field
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field & [props]]
  (let [{:as   props
         :keys [inline?
                multi-line
                wrap
                unwrap
                wrapper-class
                on-save
                persisted-value]
         :or   {wrap   identity
                unwrap identity}} (merge props (:props (meta ?field)))
        blur!   (fn [e] (j/call-in e [:target :blur]))
        cancel! (fn [e]
                  (reset! ?field persisted-value)
                  (blur! e))
        props   (v/merge-props props
                               (common-props ?field
                                             (j/get-in [:target :value])
                                             (merge {:wrap   #(when-not (str/blank? %) %)
                                                     :unwrap #(or % "")} props))

                               {:class       ["pr-8 rounded"
                                              (if inline?
                                                "form-inline"
                                                "default-ring")
                                              (when (:invalid (forms/types (forms/visible-messages ?field)))
                                                "outline-invalid")]
                                :placeholder (or (:placeholder props)
                                                 (when inline? (or (:label props) (:label ?field))))
                                :on-key-down
                                (keydown-handler {(if multi-line
                                                    :Meta-Enter
                                                    :Enter) #(when on-save
                                                               (j/call % :preventDefault)
                                                               (maybe-save-field ?field props @?field))
                                                  :Escape   blur!
                                                  :Meta-.   cancel!})})]
    (v/x
      [input-wrapper
       {:class wrapper-class}
       (when-not inline? (show-label ?field (:label props)))
       [:div.flex-v.relative
        (if multi-line
          [auto-size (v/merge-props {:class "form-text w-full"} (pass-props props))]
          [:input.form-text (pass-props props)])
        (show-postfix ?field props)
        (when (:loading? ?field)
          [:div.loading-bar.absolute.bottom-0.left-0.right-0 {:class "h-[3px]"}])]
       (show-field-messages ?field)])))

(defn wrap-prose [value]
  (when-not (str/blank? value)
    {:prose/format :prose.format/markdown
     :prose/string value}))

(def unwrap-prose :prose/string)

(defview prose-field [?field props]
  ;; TODO
  ;; multi-line markdown editor with formatting
  (text-field ?field (merge {:wrap       wrap-prose
                             :unwrap     unwrap-prose
                             :multi-line true}
                            props)))
(defn parse-video-url [url]
  (try
    (or (when-let [id (some #(second (re-find % url)) [#"youtube\.com/watch\?v=([^&?\s]+)"
                                                       #"youtube\.com/embed/([^&?\s]+)"
                                                       #"youtube\.com/v/([^&?\s]+)"
                                                       #"youtu\.be/([^&?\s]+)"])]
          {:type            :youtube
           :youtube/id      id
           :video/url       url
           :video/thumbnail (str "https://img.youtube.com/vi/" id "/hqdefault.jpg")})
        (when-let [id (some #(second (re-find % url)) [#"vimeo\.com/(?:video/)?(\d+)"
                                                       #"vimeo\.com/channels/.*/(\d+)"])]
          {:type            :vimeo
           :vimeo/id        id
           :video/url       url
           ;; 3rd party service; may be unreliable
           ;; https://stackoverflow.com/questions/1361149/get-img-thumbnails-from-vimeo
           ;; https://github.com/ThatGuySam/vumbnail
           :video/thumbnail (str "https://vumbnail.com/" id ".jpg")}))
    (catch js/Error e nil)))

(defview video-field
  {:key goog/getUid}
  [?field {:as props :keys [can-edit?]}]
  (let [!editing? (h/use-state (nil? @?field))]
    [input-wrapper
     ;; preview shows persisted value?
     [:div.flex.items-center
      [:div.flex-auto (show-label ?field (:label props))]
      (when can-edit?
        [:div.place-self-end [:a {:on-click #(swap! !editing? not)}
                              [(if @!editing? icons/dash icons/chevron-down) "icon-gray"]]])]

     (when (and can-edit? @!editing?)
       (text-field ?field (merge props
                                 {:label       nil
                                  :placeholder "YouTube or Vimeo url"
                                  :wrap        (partial hash-map :video/url)
                                  :unwrap      :video/url})))
     (when-let [url (:video/url @?field)]
       [:a.bg-black.w-full.aspect-video.flex.items-center.justify-center.group.relative
        {:href   url
         :target "_blank"
         :style  {:background-image    (css-url (:video/thumbnail (parse-video-url url)))
                  :background-size     "cover"
                  :background-position "center"}}
        [icons/external-link "absolute text-white top-2 right-2 icon-sm drop-shadow"]
        [icons/play-circle "icon-xl text-white drop-shadow-2xl transition-all group-hover:scale-110 "]])]))

(defview checkbox-field
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field props]
  ;; TODO handle on-save
  (let [messages (forms/visible-messages ?field)
        loading? (:loading? ?field)
        props    (-> (v/merge-props (common-props ?field
                                                  (j/get-in [:target :checked])
                                                  props)
                                    {:type      "checkbox"
                                     :on-blur   (forms/blur-handler ?field)
                                     :on-focus  (forms/focus-handler ?field)
                                     :on-change #(let [value (.. ^js % -target -checked)]
                                                   (maybe-save-field ?field props value))
                                     :class     [(when loading? "invisible")
                                                 (if (:invalid (forms/types messages))
                                                   "outline-invalid"
                                                   "outline-default")]})
                     (set/rename-keys {:value :checked})
                     (update :checked boolean))
        ]
    [:div.flex.flex-col.gap-1.relative
     [:label.relative.flex.items-center
      (when loading?
        [:div.h-5.w-5.inline-flex.items-center.justify-center.absolute
         [loading:spinner "h-3 w-3"]])
      [:input.h-5.w-5.rounded.border-gray-300.text-primary
       (pass-props props)]
      [:div.flex-v.gap-1.ml-2
       (when-let [label (:label ?field)]
         [:div.flex.items-center.h-5 label])
       (when (seq messages)
         (into [:div.text-gray-500] (map view-message) messages))]]]))

(defn field-props [?field & [props]]
  (v/props (:props (meta ?field)) props))

(defn filter-field [?field & [attrs]]
  (let [loading? (or (:loading? ?field) (:loading? attrs))]
    [:div.flex.relative.items-stretch.flex-auto
     [:input.pr-9.border.border-gray-300.w-full.rounded-lg.p-3
      (v/props (common-props ?field (j/get-in [:target :value]) {:unwrap #(or % "")})
               {:class       ["outline-none focus-visible:outline-4 outline-offset-0 focus-visible:outline-gray-200"]
                :placeholder "Search..."
                :on-key-down #(when (= "Escape" (.-key ^js %))
                                (reset! ?field nil))})]
     [:div.absolute.top-0.right-0.bottom-0.flex.items-center.pr-3
      {:class "text-txt/40"}
      (cond loading? (icons/loading "w-4 h-4 rotate-3s")
            (seq @?field) [:div.contents.cursor-pointer
                           {:on-click #(reset! ?field nil)}
                           (icons/close "w-5 h-5")]
            :else (icons/search "w-6 h-6"))]]))

(defn error-view [{:keys [error]}]
  (when error
    [:div.px-body.my-4
     [:div.text-destructive.border-2.border-destructive.rounded.shadow.p-4
      (str error)]]))

(defn upload-icon [class]
  [:svg {:class class :width "15" :height "15" :viewBox "0 0 15 15" :fill "none" :xmlns "http://www.w3.org/2000/svg"} [:path {:d "M7.81825 1.18188C7.64251 1.00615 7.35759 1.00615 7.18185 1.18188L4.18185 4.18188C4.00611 4.35762 4.00611 4.64254 4.18185 4.81828C4.35759 4.99401 4.64251 4.99401 4.81825 4.81828L7.05005 2.58648V9.49996C7.05005 9.74849 7.25152 9.94996 7.50005 9.94996C7.74858 9.94996 7.95005 9.74849 7.95005 9.49996V2.58648L10.1819 4.81828C10.3576 4.99401 10.6425 4.99401 10.8182 4.81828C10.994 4.64254 10.994 4.35762 10.8182 4.18188L7.81825 1.18188ZM2.5 9.99997C2.77614 9.99997 3 10.2238 3 10.5V12C3 12.5538 3.44565 13 3.99635 13H11.0012C11.5529 13 12 12.5528 12 12V10.5C12 10.2238 12.2239 9.99997 12.5 9.99997C12.7761 9.99997 13 10.2238 13 10.5V12C13 13.104 12.1062 14 11.0012 14H3.99635C2.89019 14 2 13.103 2 12V10.5C2 10.2238 2.22386 9.99997 2.5 9.99997Z" :fill "currentColor" :fill-rule "evenodd" :clip-rule "evenodd"}]])

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
(routing/path-for `assets/upload!)

(defview image-field [?field props]
  (let [src            (asset-src @?field :card)
        loading?       (:loading? ?field)
        !selected-blob (h/use-state nil)
        !dragging?     (h/use-state false)
        thumbnail      (use-loaded-image src @!selected-blob)
        on-file        (fn [file]
                         (forms/touch! ?field)
                         (reset! !selected-blob (js/URL.createObjectURL file))
                         (with-submission [asset (routing/POST `assets/upload! (doto (js/FormData.)
                                                                                 (.append "files" file)))
                                           :form ?field]
                           (reset! ?field asset)
                           (maybe-save-field ?field props asset)))
        !input         (h/use-ref)]
    ;; TODO handle on-save
    [:label.gap-2.flex-v.relative
     {:for (field-id ?field)}
     (show-label ?field (:label props))
     [:button.flex-v.items-center.justify-center.p-3.gap-3.relative.default-ring
      {:on-click      #(j/call @!input :click)
       :class         ["rounded-lg"
                       (if @!dragging?
                         "outline-2 outline-focus-accent")]
       :on-drag-over  (fn [^js e]
                        (.preventDefault e)
                        (reset! !dragging? true))
       :on-drag-leave (fn [^js e]
                        (reset! !dragging? false))
       :on-drop       (fn [^js e]
                        (.preventDefault e)
                        (some-> (j/get-in e [:dataTransfer :files 0]) on-file))}
      (when loading?
        [icons/loading "w-4 h-4 absolute top-0 right-0 text-txt/40 mx-2 my-3"])
      [:div.block.relative.rounded.cursor-pointer.flex.items-center.justify-center.rounded-lg
       (v/props {:class "text-muted-txt hover:text-txt w-32 h-32"}
                (when thumbnail
                  {:class "bg-contain bg-no-repeat bg-center"
                   :style {:background-image (css-url thumbnail)}}))

       (when-not thumbnail
         (upload-icon "w-5 h-5 m-auto"))

       [:input.hidden
        {:id        (field-id ?field)
         :ref       !input
         :type      "file"
         :accept    "image/webp, image/jpeg, image/gif, image/png, image/svg+xml"
         :on-change #(some-> (j/get-in % [:target :files 0]) on-file)}]]
      (show-field-messages ?field)]]))

(defview images-field [?field {:as props :keys [label]}]
  (let [images (->> (:images/order @?field)
                    (map (fn [id]
                           {:url       (asset-src (db/entity [:entity/id id]) :card)
                            :entity/id id})))]
    (for [{:keys [entity/id url]} images]
      ;; TODO
      ;; upload image,
      ;; re-order images
      [:div.relative {:key url}
       [:div.inset-0.bg-black.absolute.opacity-10]
       [:img {:src url}]])))

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
  [{:as match :match/keys [endpoints params]}]
  (if-let [view (-> endpoints :view :endpoint/sym (@routing/!views))]
    (when view
      [view (assoc params :account-id (db/get :env/config :account-id))])
    [pprinted match]))

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

(def form-classes "flex flex-col gap-4 p-6 max-w-lg mx-auto bg-back relative text-sm")
(def btn-primary :button.btn.btn-primary.px-6.py-3.self-start.text-base)

(v/defview submit-form [!form label]
  [:<>
   (show-field-messages !form)
   [btn-primary {:type     "submit"
                 :disabled (not (forms/submittable? !form))}
    label]])

(defview redirect [to]
  (h/use-effect #(routing/nav! to)))

(defn initials [display-name]
  (let [words (str/split display-name #"\s+")]
    (str/upper-case
      (str/join "" (map first
                        (if (> (count words) 2)
                          [(first words) (last words)]
                          words))))))

(defn avatar [{:as props :keys [size] :or {size 6}}
              {:keys [account/display-name
                      entity/title
                      image/avatar]}]
  (let [class (v/classes [(str "w-" size)
                          (str "h-" size)
                          "flex-none rounded-full"])
        props (dissoc props :size)]
    (or
      (when-let [src (asset-src avatar :avatar)]
        [:div.bg-no-repeat.bg-center.bg-contain
         (v/merge-props {:style {:background-image (css-url src)}
                         :class class}
                        props)])
      (when-let [txt (or display-name title)]
        [:div.bg-gray-200.text-gray-600.inline-flex.items-center.justify-center
         (v/merge-props {:class class} props)
         (initials txt)]))))

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

(defview truncate-items [{:keys [limit expander unexpander]
                          :or   {expander   (fn [n]
                                              [:div.flex-v.items-center.text-center.py-1.cursor-pointer.bg-gray-50.hover:bg-gray-100.rounded-lg.text-gray-600
                                               #_[icons/chevron-down "w-6 h-6"]
                                               #_[icons/ellipsis-horizontal "w-8"]
                                               (str "+ " n)])
                                 unexpander [:div.flex-v.items-center.text-center.py-1.bg-gray-50.hover:bg-gray-100.rounded-lg.text-gray-600 [icons/chevron-up "w-6 h-6"]]}} items]
  (let [item-count  (count items)
        !expanded?  (h/use-state false)
        expandable? (> item-count limit)]
    (cond (not expandable?) items
          @!expanded? [:<> items [:div.contents {:on-click #(reset! !expanded? false)} unexpander]]
          :else [:<> (take limit items) [:div.contents {:on-click #(reset! !expanded? true)} (expander (- item-count limit))]])))

(def hero (v/from-element :div.rounded-lg.bg-gray-100.p-6.width-full))

(defn use-autofocus-ref []
  (h/use-callback (fn [^js x]
                    (when x
                      (if (.matches x "input, textarea")
                        (.focus x)
                        (some-> (.querySelector x "input, textarea") .focus))))))

(def read-string (partial edn/read-string {:readers {'uuid uuid}}))

(defn prevent-default [f]
  (fn [^js e]
    (.preventDefault e)
    (f e)))

(defn pprint [x] (clojure.pprint/pprint x))

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