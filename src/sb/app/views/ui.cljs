(ns sb.app.views.ui
  (:require-macros [sb.app.views.ui :as ui])
  (:require ["prosemirror-keymap" :refer [keydownHandler]]
            ["markdown-it" :as md]
            ["linkify-element" :as linkify-element]
            ["react" :as react]
            [applied-science.js-interop :as j]
            [cljs.reader :as edn]
            [clojure.pprint]
            [clojure.string :as str]
            [inside-out.forms :as io]
            [inside-out.macros]
            [promesa.core :as p]
            [re-db.api :as db]
            [re-db.react]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.entity.data :as entity.data]
            [sb.app.views.radix :as radix]
            [sb.client.sanitize :as sanitize]
            [sb.i18n]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.util :as u]
            [shadow.cljs.modern :refer [defclass]]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [net.cgrand.xforms :as xf]))

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
    (v/x [:div {:class                   "overflow-hidden"
                :ref                     !ref
                :dangerouslySetInnerHTML #js{:__html ""}}])))

(defn filter-value [entity]
  (case (:entity/kind entity)
    :account (:account/display-name entity)
    :membership (-> (:membership/member entity) :account/display-name)
    (:entity/title entity)))

(defn match-entity [match-text entity]
  (if match-text
    (some->> (filter-value entity) (re-find (re-pattern (str "(?i)" match-text))))
    true))

(defn filtered [match-text]
  (filter (partial match-entity match-text)))

(defn match-pred [match-text]
  #(some->> (filter-value %) (re-find (re-pattern (str "(?i)" match-text)))))

(defn tag-pred [tag-id]
  #(some (comp #{tag-id} :tag/id) (:entity/tags %)))

;; TODO / WIP
;; - some sorting widgets are dynamic (fields that have the "filter by this field" flag)
(defn sorted [sort-key & {:keys [direction field-id field-options] :or {direction :asc}}]
  (case sort-key
    ;; TODO define default-sort for all entity kinds that are sorted
    :default (xf/sort-by (complement :project/sticky?))
    :entity/created-at (xf/sort-by :entity/created-at (case direction :asc compare :desc u/compare:desc))
    :random (xf/sort #(rand-nth [-1 1]))
    :field.type/select (let [field-positions (u/entry-indexes (map :field-option/value field-options))
                             field-labels (u/index-by field-options :field-option/value :field-option/label)]
                         (comp (xf/sort-by (comp field-positions :select/value #(get % field-id) :entity/field-entries))
                               (map #(-> (:entity/field-entries %)
                                         (get field-id)
                                         :select/value
                                         field-labels
                                         (->> (vary-meta % assoc :group/label))))))
    (do (js/console.warn (str "no sort defined for " (pr-str sort-key)))
        identity)))

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

(defn use-last-loaded [image-url deps]
  (let [!last-loaded (h/use-state-with-deps image-url deps)
        !loaded      (h/use-state #{})]
    (h/use-effect
      (fn []
        (when (and image-url (not (@!loaded image-url)))
          (let [^js img (doto (js/document.createElement "img")
                          (j/assoc-in! [:style :display] "none")
                          (js/document.body.appendChild))]
            (j/assoc! img
                      :onload #(do (swap! !loaded conj image-url)
                                   (.remove img))
                      :src image-url)))
        (when (and image-url
                   (not= image-url @!last-loaded)
                   (@!loaded image-url))
          (reset! !last-loaded image-url))
        nil)
      [image-url (@!loaded image-url)])
    [@!last-loaded (not= @!last-loaded image-url)]))

(defn use-last-some [value]
  (let [!last-value (h/use-state value)]
    (h/use-effect (fn []
                    (when (and (some? value) (not= value @!last-value))
                      (reset! !last-value value))
                    nil)
                  (h/use-deps value))
    @!last-value))

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
                          "flex-none rounded"])
        props (dissoc props :size)]
    (or
      (when-let [src (asset.ui/asset-src avatar :avatar)]
        [:img.object-cover (v/merge-props {:class class :src src} props)])
      (when-let [txt (or display-name title)]
        [:div.bg-gray-200.text-gray-600.inline-flex-center
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
          @!expanded? [:<> (seq items) [:div.contents {:on-click #(reset! !expanded? false)} unexpander]]
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

(defn element-center-y [el]
  (j/let [^js {:keys [y height]} (j/call el :getBoundingClientRect)]
    (+ y (/ height 2))))
(defn element-center-x [el]
  (j/let [^js {:keys [x width]} (j/call el :getBoundingClientRect)]
    (+ x (/ width 2))))

(defn re-order [xs source side destination]
  {:post [(= (count %) (count xs))]}
  (let [out (reduce (fn [out x]
                      (if (= x destination)
                        (into out (case side :before [source destination]
                                             :after [destination source]))
                        (conj out x)))
                    []
                    (remove #{source} xs))]
    (when-not (= (count out) (count xs))
      (throw (ex-info "re-order failed, destination not found" {:source source :destination destination})))
    out))

(defn use-orderable-parent
  [?parent {:keys [axis] :or {axis :y}}]
  (let [group         (goog/getUid ?parent)
        transfer-data (fn [e data]
                        (j/call-in e [:dataTransfer :setData]
                                   (str group)
                                   (pr-str data))
                        (j/assoc-in! e [:dataTransfer :effectAllowed] "move")
                        )

        receive-data  (fn [e]
                        (try
                          (read-string (j/call-in e [:dataTransfer :getData] (str group)))
                          (catch js/Error e nil)))
        data-matches? (fn [e]
                        (some #{(str group)} (j/get-in e [:dataTransfer :types])))
        [drag-id set-drag!] (h/use-state nil)
        [[drop-id drop-type] set-drop!] (h/use-state nil)]
    (fn [?child]
      (let [id            (:sym ?child)
            drop-type     (when (= drop-id id) drop-type)
            !should-drag? (h/use-ref false)
            dragging?     (= drag-id id)
            on-move       (fn [{:keys [source side destination]}]
                            (io/swap-many-children! ?parent re-order
                                                    (get ?parent source)
                                                    side
                                                    (get ?parent destination))
                            (entity.data/maybe-save-field ?child))]
        {:drag-handle-props  {:on-mouse-down #(reset! !should-drag? true)
                              :on-mouse-up   #(reset! !should-drag? false)}
         :drag-subject-props {:draggable     true
                              :data-dragging dragging?
                              :data-dropping (some? drop-type)
                              :on-drag-over  (j/fn [^js {:as e :keys [clientX
                                                                      clientY
                                                                      currentTarget]}]
                                               (j/call e :preventDefault)
                                               (.persist e)
                                               (when (data-matches? e)
                                                 (set-drop!
                                                   (cond (= drag-id id) nil
                                                         (= ?child (last ?parent)) (if (case axis
                                                                                         :y (< clientY (element-center-y currentTarget))
                                                                                         :x (< clientX (element-center-x currentTarget)))
                                                                                     [id :before]
                                                                                     [id :after])
                                                         :else [id :before]))))
                              :on-drag-leave (fn [^js e]
                                               (j/call e :preventDefault)
                                               (set-drop! nil))
                              :on-drop       (fn [^js e]
                                               (.preventDefault e)
                                               (set-drop! nil)
                                               (when-let [source (receive-data e)]
                                                 (when-not (= source id)
                                                   (on-move {:destination id
                                                             :source      source
                                                             :side        drop-type}))))
                              :on-drag-end   (fn [^js e]
                                               (set-drag! nil))
                              :on-drag-start (fn [^js e]
                                               (if @!should-drag?
                                                 (do
                                                   (set-drag! id)
                                                   (transfer-data e id))
                                                 (.preventDefault e)))}
         :dragging           dragging?
         :dropping           drop-type
         :drop-indicator     (when drop-type
                               (case axis
                                 :y (v/x [:div.absolute.bg-focus-accent
                                          {:class ["h-[4px] z-[99] inset-x-0 rounded"
                                                   (case drop-type
                                                     :before "top-[-2px]"
                                                     :after "bottom-[-2px]" nil)]}])
                                 :x (v/x [:div.absolute.bg-focus-accent
                                          {:class ["w-[4px] z-[99] inset-y-0 rounded"
                                                   (case drop-type
                                                     :before "left-[-2px]"
                                                     :after "right-[-2px]" nil)]}])))}))))

(ui/defview action-button [{:as props :keys [on-click]} child]
  (let [!async-state (h/use-state nil)
        on-click     (fn [e]
                       (reset! !async-state {:loading? true})
                       (p/let [result (on-click e)]
                         (reset! !async-state (when (:error result) result))))
        {:keys [loading? error]} @!async-state]
    [radix/tooltip {:delay-duration 0} error
     [:div.btn.btn-white.overflow-hidden.relative.py-2
      (-> props
          (v/merge-props {:class (when error "ring-destructive ring-2")})
          (assoc :on-click (when-not loading? on-click)))
      child
      (when (:loading? @!async-state)
        [:div.loading-bar.absolute.top-0.left-0.right-0.h-1])]]))

(defn small-timestamp [date]
  (let [now (js/Date.)
        current-year? (= (.getYear now)
                         (.getYear date))
        current-month? (and current-year? (= (.getMonth now)
                                             (.getMonth date)))
        current-day? (and current-month? (= (.getDate now)
                                            (.getDate date)))]
    (.format (js/Intl.DateTimeFormat. js/undefined
                                      (clj->js (merge {:minute :numeric
                                                       :hour :numeric}
                                                      (when-not current-day?
                                                        {:day :numeric
                                                         :weekday :long
                                                         :month :long})
                                                      (when-not current-year?
                                                        {:year :numeric}))))
             date)))
