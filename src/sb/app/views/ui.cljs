(ns sb.app.views.ui
  (:require-macros [sb.app.views.ui :as ui])
  (:require ["@radix-ui/react-dropdown-menu" :as dm]
            ["prosemirror-keymap" :refer [keydownHandler]]
            ["markdown-it" :as md]
            ["linkify-element" :as linkify-element]
            ["react" :as react]
            [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [clojure.pprint]
            [clojure.string :as str]
            [inside-out.macros]
            [promesa.core :as p]
            [re-db.api :as db]
            [re-db.react]
            [sb.app.asset.ui :as asset.ui]
            [sb.client.sanitize :as sanitize]
            [sb.i18n]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [shadow.cljs.modern :refer [defclass]]
            [taoensso.tempura :as tempura]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [sb.i18n :as i18n]))

(defn dev? [] (= "dev" (db/get :env/config :env)))

(defn loading-bar [& [class]]
  [:div.relative
   {:class class}
   [:div.loading-bar]])

(defonce ^js Markdown (md))

(ui/defview show-markdown
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

(defn filtered [match-text]
  (comp
    (remove :entity/archived?)
    (filter (if match-text
              #(re-find (re-pattern (str "(?i)" match-text)) (:entity/title %))
              identity))))

(defn pprinted [x & _]
  [:pre.whitespace-pre-wrap (with-out-str (clojure.pprint/pprint x))])

(def safe-html sanitize/safe-html)

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

(defn keydown-handler [bindings]
  (let [handler (keydownHandler (reduce-kv (fn [out k f]
                                             (j/!set out (name k) (fn [_ _ _] (f js/window.event)))) #js{} bindings))]
    (fn [e] (handler #js{} e))))

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

(ui/defview show-async-status
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

(ui/defview show-match
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

(ui/defview redirect [to]
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
      (when-let [src (asset.ui/asset-src avatar :avatar)]
        [:div.bg-no-repeat.bg-center.bg-contain
         (v/merge-props {:style {:background-image (asset.ui/css-url src)}
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

(ui/defview truncate-items [{:keys [limit expander unexpander]
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