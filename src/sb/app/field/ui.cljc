(ns sb.app.field.ui
  (:require #?(:cljs ["@radix-ui/react-popover" :as Pop])
            [applied-science.js-interop :as j]
            [clojure.set :as set]
            [clojure.string :as str]
            [inside-out.forms :as io]
            [inside-out.forms :as forms]
            [sb.app.asset.data :as asset.data]
            [sb.app.asset.ui :as asset.ui]
            [sb.app.entity.data :as entity.data]
            [sb.app.field.data :as field.data]
            [sb.app.field.data :as data]
            [sb.app.form.ui :as form.ui]
            [sb.app.views.radix :as radix]
            [sb.app.views.ui :as ui]
            [sb.client.sanitize :as sanitize]
            [sb.icons :as icons]
            [sb.routing :as routing]
            [sb.schema :as sch]
            [sb.util :as u]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [sb.color :as color]
            [sb.i18n :refer [t]]
            [promesa.core :as p]
            [re-db.api :as db]))

#?(:cljs
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
       (catch js/Error e nil))))

(ui/defview show-prose [{:as m :prose/keys [format string]}]
  (when m
    (case format
      :prose.format/html [sanitize/safe-html string]
      :prose.format/markdown [ui/show-markdown string])))

(v/defview auto-size [props]
  (let [v!    (h/use-state "")
        props (merge props {:value     (or (:value props @v!) "")
                            :on-change (:on-change props
                                         #(reset! v! (j/get-in % [:target :value])))})]
    [:div.auto-size
     [:div (select-keys props [:class :style])
      (str (:value props) " ")]
     [:textarea (assoc props :rows 1)]]))

(ui/defview checkbox-field
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field props]
  (let [messages (forms/visible-messages ?field)
        loading? (:loading? ?field)
        {:keys [field/classes]} props
        props    (-> (v/merge-props (form.ui/?field-props ?field
                                                          (merge {:field/event->value (comp boolean (j/get-in [:target :checked]))}
                                                                 props))
                                    {:type      "checkbox"
                                     :on-blur   (forms/blur-handler ?field)
                                     :on-focus  (forms/focus-handler ?field)
                                     :on-change #(let [value (boolean (.. ^js % -target -checked))]
                                                   (reset! ?field value)
                                                   (entity.data/maybe-save-field ?field))
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
        [:div.h-5.w-5.inline-flex-center.absolute
         [ui/loading:spinner "h-3 w-3"]])
      [:input.h-5.w-5.rounded.border-gray-300.text-primary
       (u/dissoc-qualified props)]
      [:div.flex-v.gap-1.ml-2
       (when-let [label (form.ui/get-label ?field (:field/label props))]
         [:div.flex.items-center.h-5 label])
       (when (seq messages)
         (into [:div.text-gray-500] (map form.ui/view-message) messages))]]]))

#?(:cljs
   (defn with-messages-popover [?field anchor]
     (ui/error-popover anchor (form.ui/show-field-messages ?field))))

(ui/defview text-field
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field props]
  (let [{:as         props
         :field/keys [classes
                      multi-line?
                      wrap
                      unwrap
                      can-edit?
                      unstyled?
                      keybindings]
         :or         {wrap   identity
                      unwrap identity}} (merge props (:props (meta ?field)))
        blur!      (fn [e] (j/call-in e [:target :blur]))
        cancel!    (fn [^js e]
                     (.preventDefault e)
                     (.stopPropagation e)
                     (reset! ?field (entity.data/persisted-value ?field))
                     (js/setTimeout #(blur! e) 0))
        props      (v/merge-props props
                                  (form.ui/?field-props ?field
                                                        (merge {:field/event->value (j/get-in [:target :value])
                                                                :field/wrap         #(when-not (str/blank? %) %)
                                                                :field/unwrap       #(or % "")}
                                                               props)))
        classes    (merge (if (or unstyled?
                                  (not can-edit?))
                            {:wrapper "w-full"
                             :input   (str "border-0 border-b-2 border-transparent text-inherit-all p-0 font-inherit focus:ring-0 "
                                           "focus:border-focus-accent")}
                            {:wrapper "w-full"
                             :input   "form-text rounded default-ring"})
                          classes)
        data-props {:data-touched (:touched ?field)
                    :data-invalid (not (io/valid? ?field))
                    :data-focused (:focused ?field)}
        !input-ref (h/use-ref)
        focused?   (some-> @!input-ref (= (j/get js/window.document :activeElement)))]
    ;; TODO ... shouldn't ?field retain :focused/:touched metadata when its persisted value changes?
    ;; currently we create a new ?field when the persisted value changes, which is a bit of a hack
    (when (or can-edit? (u/some-str (:value props)))
      [:div.field-wrapper
       (merge data-props {:class (:wrapper classes)})
       (form.ui/show-label ?field (:field/label props) (:label classes))
       [:div.flex-v.relative
        (with-messages-popover ?field
          [auto-size (-> (u/dissoc-qualified props)
                         (v/merge-props
                           data-props
                           {:ref         !input-ref
                            :disabled    (not can-edit?)
                            :class       ["w-full" (:input classes)]
                            :placeholder (:placeholder props)
                            :on-key-down (let [save (fn [^js e]
                                                      (if (io/ancestor-by ?field :field/persisted?)
                                                        (entity.data/maybe-save-field ?field)
                                                        (some-> (j/get-in e [:target :form])
                                                                (j/call :requestSubmit)))
                                                      (.preventDefault e))]
                                           (ui/keydown-handler (merge {:Escape cancel!
                                                                       :Meta-. cancel!}
                                                                      (if multi-line?
                                                                        {:Meta-Enter save}
                                                                        {:Enter save})
                                                                      keybindings)))})
                         (cond-> (not can-edit?)
                                 (update :value #(some-> % str/trim))))])
        ;; show pencil when value is modified
        (when (and (or focused? (:touched ?field))
                   (io/closest ?field :field/persisted?)
                   (not= (u/some-str (entity.data/persisted-value ?field))
                         (u/some-str (:value props))))
          [:div.pointer-events-none.absolute.right-0.top-0.flex.items-center.p-2 [icons/pencil-outline "w-4 h-4 text-txt/40"]])

        (when (:loading? ?field)
          [:div.loading-bar.absolute.bottom-0.left-0.right-0 {:class "h-[3px]"}])]
       (when-let [hint (and (:focused ?field)
                            (:field/hint props))]
         [:div.text-gray-500.text-sm hint])])))

(ui/defview filter-field [?field attrs]
  [:div.relative.flex-auto.flex
   [text-field ?field {:field/can-edit? true
                       :field/classes   {:wrapper "flex-auto items-stretch"
                                         :input   "form-text rounded default-ring pr-9"}
                       :placeholder     (t :tr/search)}]
   [:div.absolute.top-0.right-0.bottom-0.flex.items-center.pr-3
    {:class "text-txt/40"}
    (icons/search "w-6 h-6")]])

(defn wrap-prose [value]
  (when-not (str/blank? value)
    {:prose/format :prose.format/markdown
     :prose/string value}))

(def unwrap-prose :prose/string)

(ui/defview prose-field
  {:make-?field (fn [init _props]
                  (io/form (-> {:prose/format (prose/?format :init :prose.format/markdown)
                                :prose/string prose/?string}
                               (u/guard :prose/string))
                           :init init))}
  [{:as ?prose-field :prose/syms [?format ?string]} props]
  ;; TODO
  ;; multi-line markdown editor with formatting
  (text-field ?string (merge {:field/multi-line? true}
                             props)))

(comment

  (defn youtube-embed [video-id]
    [:iframe#ytplayer {:type        "text/html" :width 640 :height 360
                       :frameborder 0
                       :src         (str "https://www.youtube.com/embed/" video-id)}])
  (video-field [:field.video/youtube-sdgurl "gMpYX2oev0M"])
  )

(ui/defview show-video-url [url]
  [:a.bg-black.w-full.aspect-video.flex-center.relative
   {:href   url
    :target "_blank"
    :style  {:background-image    (asset.ui/css-url (:video/thumbnail (parse-video-url url)))
             :background-size     "cover"
             :background-position "center"}}
   [:div.rounded.p-1.absolute.top-1.right-1.text-white {:class "hover:bg-black/20"}
    [icons/external-link " icon-lg drop-shadow"]]

   [icons/play-circle "icon-xl w-20 h-20 text-white drop-shadow-2xl transition-all hover:scale-110 "]])

(ui/defview video-field
  {:key (fn [?field _] #?(:cljs (goog/getUid ?field)))}
  [?field {:as props :keys [field/can-edit?]}]
  (let [value (u/some-str @?field)]
    (when (or can-edit? value)
      [:div.field-wrapper
       ;; preview shows persisted value?
       [:div.flex.items-center
        (when (and can-edit? (not value))
          [:div.flex-auto (form.ui/show-label ?field (:field/label props))])]
       (when-let [url (:video/url value)]
         [show-video-url url])
       (when can-edit?
         (text-field ?field (merge props
                                   {:field/label  false
                                    :field/wrap   (fn [v] (when-not (str/blank? v) {:video/url v}))
                                    :field/unwrap :video/url
                                    :placeholder  "YouTube or Vimeo url"})))])))

(defn show-select-value [{:keys [field/options]} value]
  (let [{:keys [field-option/label
                field-option/color]
         :or   {color "#dddddd"}} (u/find-first options #(= value (:field-option/value %)))]
    [:div.inline-flex.items-center.gap-1.rounded.whitespace-nowrap.py-1.px-3.mr-auto
     {:style (color/color-pair color)}
     label]))

(ui/defview select-field [?field {:as props :field/keys [wrap
                                                         unwrap
                                                         label
                                                         options
                                                         can-edit?
                                                         classes]
                                  :or {unwrap identity
                                       wrap   identity}}]
  [:div.field-wrapper {:class (:wrapper classes)}
   (form.ui/show-label ?field label)
   (if can-edit?
     (with-messages-popover ?field
       [radix/select-menu (-> (form.ui/?field-props ?field (merge {:field/event->value identity}
                                                                  props))
                              (set/rename-keys {:on-change :on-value-change})
                              (assoc :on-value-change (fn [v]
                                                        (reset! ?field (wrap v))
                                                        (entity.data/maybe-save-field ?field))
                                     :field/can-edit? (:field/can-edit? props)
                                     :field/options (->> options
                                                         (map (fn [{:as opt :field-option/keys [label value color]}]
                                                                {:text  label
                                                                 :value (unwrap value)}))
                                                         doall)))])
     [show-select-value props @?field])

   (when (:loading? ?field)
     [:div.loading-bar.absolute.bottom-0.left-0.right-0 {:class "h-[3px]"}])])

(ui/defview color-field*
  ;; color field must be contained within a relative.overflow-hidden element, which it expands to fill.
  [?field {:as props :keys [field/color-list]}]
  (let [list-id (str (goog/getUid ?field) "-colors")]
    [:<>
     (when (seq color-list)
       [:datalist {:id list-id}
        (doall
          (for [color color-list]
            [:option {:value color :key color}]))])
     [:input.default-ring.default-ring-hover.rounded
      (-> (form.ui/?field-props ?field
                                (merge props
                                       {:field/event->value    (j/get-in [:target :value])
                                        :field/save-on-change? true}))
          (v/merge-props {:list  (when (seq color-list) list-id)
                          :style {:top      -10
                                  :left     -10
                                  :width    100
                                  :height   100
                                  :position "absolute"}} props)
          (assoc :type "color")
          (update :value #(or % "#ffffff"))
          (u/dissoc-qualified))]]))

(ui/defview color-field-with-label [?field {:as props :keys [field/label]}]
  [:div.flex.gap-3.items-center
   (form.ui/show-label ?field label)
   (with-messages-popover ?field
     [:div.relative.w-5.h-5.overflow-hidden.outline.outline-black.outline-1.rounded [color-field* ?field props]])])

(ui/defview plural-item-form [{:as   props
                               :keys [?items
                                      make-?item
                                      edit-?item
                                      init
                                      on-submit
                                      close!]}]
  (let [?item   (h/use-memo #(make-?item init props) (h/use-deps init))
        submit! #(on-submit ?item close!)]
    [:el.relative Pop/Root {:open true :on-open-change #(close!)}
     [:el Pop/Anchor]
     [:el Pop/Content {:as-child true}
      [:div.p-2.z-10 {:class radix/float-small}
       (edit-?item ?item submit!)
       (form.ui/show-field-messages ?items)]]]))

(ui/defview show-plural-item
  {:key (fn [_ ?item] #?(:cljs (goog/getUid ?item)))}
  [{:as   props
    :keys [use-order
           ?items
           !editing
           show-?item
           field/can-edit?]} ?item]
  (let [{:keys [drag-handle-props
                drag-subject-props
                dragging
                dropping]} (use-order ?item)]
    (if can-edit?
      [radix/context-menu {:trigger [:div.transition-all
                                     (v/merge-props drag-handle-props
                                                    drag-subject-props
                                                    {:class (cond (= dropping :before) "pl-2"
                                                                  dragging "opacity-20")})
                                     (show-?item ?item props)
                                     (when (= ?item @!editing)
                                       [plural-item-form (assoc props
                                                           :close! #(reset! !editing nil)
                                                           :init @?item
                                                           :on-submit (fn [?new-item close!]
                                                                        (reset! ?item @?new-item)
                                                                        (p/let [res (entity.data/maybe-save-field ?item)]
                                                                          (when-not (:error res)
                                                                            (reset! ?item @?new-item)
                                                                            (close!)))))])]
                           :items   [[radix/context-menu-item
                                      {:on-select (fn []
                                                    (io/remove-many! ?item)
                                                    (entity.data/maybe-save-field ?items))}
                                      (t :tr/remove)]
                                     [radix/context-menu-item
                                      {:on-select (fn [] (p/do (p/delay 0) (reset! !editing ?item)))}
                                      (t :tr/edit)]]}]
      (show-?item ?item props))))

(ui/defview plural-editor [{:as   props
                            :keys [?items
                                   field/can-edit?]}]
  (let [!editing  (h/use-state nil)
        use-order (ui/use-orderable-parent ?items {:axis :x})]
    [:div.field-wrapper
     (form.ui/show-label ?items (:field/label props))
     [:div.flex.flex-wrap.gap-1
      (map (partial show-plural-item (assoc props :!editing !editing :use-order use-order)) ?items)
      (let [!creating-new (h/use-state false)]
        (when can-edit?
          [:div.inline-flex.text-sm.gap-1.items-center.rounded.hover:bg-gray-100.p-1
           {:on-click #(reset! !creating-new true)}
           [:span.cursor-default (:add-label props (t :tr/add))]
           [icons/plus "w-4 h-4 icon-gray"]
           (when @!creating-new
             [plural-item-form (assoc props
                                 :on-submit (fn [?item close!]
                                              (io/add-many! ?items @?item)
                                              (p/let [res (entity.data/maybe-save-field ?items)]
                                                (when-not (:error res)
                                                  (io/clear! ?item)
                                                  (close!))
                                                res))
                                 :close! #(reset! !creating-new false))])]))]]))

(ui/defview badges-field
  {:make-?field (fn [init _props]
                  (io/field :many {:badge/label ?label
                                   :badge/color (?color :default "#dddddd")}
                            :init init))}
  [?badges {:as props :keys [membership/roles]}]
  (when (or (seq ?badges)
            (:role/board-admin roles))
    (plural-editor (merge props
                          {:?items          ?badges
                           :field/label     (when-not (:role/board-admin roles) false)
                           :field/can-edit? (:role/board-admin roles)
                           :make-?item      (fn [init props]
                                              (io/form {:badge/label ?label
                                                        :badge/color (?color :default "#dddddd")}
                                                       :required [?label ?color]
                                                       :init init))
                           :edit-?item      (fn [{:as ?badge :syms [?label ?color]} submit!]
                                              [:form.outline-none.flex.gap-2.items-stretch
                                               {:on-submit (fn [e]
                                                             (.preventDefault e)
                                                             (submit!))}
                                               [text-field ?label {:placeholder       (t :tr/label)
                                                                   :field/keybindings {:Enter submit!}
                                                                   :field/multi-line? false
                                                                   :field/can-edit?   true
                                                                   :field/label       false}]
                                               [:div.relative.w-10.h-10.overflow-hidden.rounded.outline.outline-black.outline-1 [color-field* ?color {:field/can-edit? true}]]
                                               [:button.flex.items-center {:type "submit"} [icons/checkmark "w-5 h-5 icon-gray"]]])
                           :show-?item      (fn [{:as   ?badge
                                                  :syms [?label ?color]} {:keys [membership/roles]}]
                                              (let [bg    (or (u/some-str @?color) "#ffffff")
                                                    color (color/contrasting-text-color bg)]
                                                (v/x [:div.rounded.bg-badge.text-badge-txt.py-1.px-2.text-sm.inline-flex
                                                      {:key   @?label
                                                       :style {:background-color bg :color color}} @?label])))}))))

(ui/defview image-field [?field props]
  (let [src            (asset.ui/asset-src @?field :card)
        loading?       (:loading? ?field)
        !selected-blob (h/use-state nil)
        !dragging?     (h/use-state false)
        thumbnail      (ui/use-loaded-image src @!selected-blob)
        on-file        (fn [file]
                         (forms/touch! ?field)
                         (reset! !selected-blob (js/URL.createObjectURL file))
                         (ui/with-submission [id (routing/POST `asset.data/upload!
                                                               (doto (js/FormData.)
                                                                 (.append "files" file)))
                                              :form ?field]
                           (reset! ?field (sch/wrap-id id))
                           (entity.data/maybe-save-field ?field)))
        !input         (h/use-ref)]
    ;; TODO handle on-save
    [:label.gap-2.flex-v.relative
     {:for (form.ui/field-id ?field)}
     (form.ui/show-label ?field (:field/label props))
     [:button.flex-v.items-center.justify-center.p-3.gap-3.relative.default-ring.default-ring-hover
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
      [:div.block.relative.rounded.cursor-pointer.flex-center.rounded-lg
       (v/props {:class "text-muted-txt hover:text-txt w-32 h-32"}
                (when thumbnail
                  {:class "bg-contain bg-no-repeat bg-center"
                   :style {:background-image (asset.ui/css-url thumbnail)}}))

       (when-not thumbnail
         (ui/upload-icon "w-5 h-5 m-auto"))

       [:input.hidden
        {:id        (form.ui/field-id ?field)
         :ref       !input
         :type      "file"
         :accept    "image/webp, image/jpeg, image/gif, image/png, image/svg+xml"
         :on-change #(some-> (j/get-in % [:target :files 0]) on-file)}]]
      (form.ui/show-field-messages ?field)]]))

(ui/defview add-image-button [?image-list]
  (let [loading?       (:loading? ?image-list)
        !selected-blob (h/use-state nil)
        !dragging?     (h/use-state false)
        thumbnail      @!selected-blob
        !input         (h/use-ref)
        on-file        (fn [file]
                         (reset! !selected-blob (js/URL.createObjectURL file))
                         (io/touch! ?image-list)
                         (ui/with-submission [id (routing/POST `asset.data/upload!
                                                               (doto (js/FormData.)
                                                                 (.append "files" file)))
                                              :form ?image-list]
                           (io/add-many! ?image-list {:entity/id (sch/unwrap-id id)})
                           (entity.data/maybe-save-field ?image-list)
                           (reset! !selected-blob nil)
                           (j/!set @!input :value nil)))]
    ;; TODO handle on-save
    [:label.absolute.inset-0.gap-2.flex-v.items-center.justify-center.p-3.gap-3.default-ring.default-ring-hover
     {:for           (form.ui/field-id ?image-list)
      :class         ["rounded"
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

     [:div.block.absolute.inset-0.rounded.flex-center.rounded-lg
      (v/props {:class "text-muted-txt hover:text-txt bg-contain bg-no-repeat bg-center"}
               (when thumbnail
                 {:style {:background-image (asset.ui/css-url thumbnail)}})
               #_{:style {:background-image "url(\"/assets/9d0dac6c-46bb-4086-8551-5bd533a9a2e8?op=bound&width=600\")"}})

      (cond loading? [:div.rounded.bg-white.p-1 [icons/loading "w-4 h-4 text-txt/60"]]
            (not thumbnail) (ui/upload-icon "w-5 h-5 m-auto"))

      [:input.hidden
       {:id        (form.ui/field-id ?image-list)
        :ref       !input
        :type      "file"
        :accept    "image/webp, image/jpeg, image/gif, image/png, image/svg+xml"
        :on-change #(some-> (j/get-in % [:target :files 0]) on-file)}]]
     ;; put messages in a popover
     (form.ui/show-field-messages ?image-list)]))


(ui/defview image-thumbnail
  {:key (fn [_ ?image] (:entity/id @?image))}
  [{:keys [!?current ?images use-order field/can-edit?]} {:as ?image :syms [?id]}]
  (let [url      (asset.ui/asset-src @?id :card)
        {:keys [drag-handle-props
                drag-subject-props
                dragging
                dropping]} (use-order ?image)
        current? (= @!?current ?image)
        img      (v/x [:img.object-contain.h-16.w-16.rounded.overflow-hidden.bg-gray-50.transition-all
                       {:src   url
                        :class [(when dragging "opacity-20 w-0")
                                (when current? "outline outline-2 outline-black")]}])]
    (if can-edit?
      [radix/context-menu
       {:key     url
        :trigger (v/x [:div.relative.transition-all
                       (v/merge-props {:class    (when (= dropping :before) "pl-4")
                                       :on-click #(reset! !?current ?image)}
                                      drag-handle-props
                                      drag-subject-props)
                       img])
        :items   [[radix/context-menu-item {:on-select (fn []
                                                         (io/remove-many! ?image)
                                                         (entity.data/maybe-save-field ?images))}
                   "Delete"]]}]
      img)))

(ui/defview images-field [?images {:as props :field/keys [label can-edit?]}]
  (let [hook-deps (h/use-deps (:init ?images))
        !?current (h/use-state-with-deps (first ?images) hook-deps)
        use-order (ui/use-orderable-parent ?images {:axis :x})
        [selected-url loading?] (ui/use-last-loaded (some-> @!?current ('?id) deref (asset.ui/asset-src :card)) hook-deps)]
    [:div.field-wrapper
     (form.ui/show-label ?images label)
     (when selected-url
       [:div.relative.flex-center {:key selected-url}
        (when loading? [icons/loading "w-4 h-4 text-txt/60 absolute top-2 right-2"])
        [:img.max-h-80 {:src selected-url}]])
     ;; thumbnails
     (when (or (> (count (seq ?images)) 1)
               can-edit?)
       [:div.flex.gap-2.flex-wrap
        (when can-edit? [:div.relative.h-16.w-16.flex-none [add-image-button ?images]])
        (->> ?images
             (map (partial image-thumbnail
                           (merge props {:use-order use-order
                                         :!?current !?current
                                         :?images   ?images}))))])]))

(ui/defview link-list-field
  ;; TODO make this editable
  {:make-?field (fn [init _props]
                  (io/field :many {}))}
  [?links {:field/keys [label]}]
  [:div.field-wrapper
   (form.ui/show-label ?links label)
   (for [{:syms [link/?text link/?url]} ?links]
     ^{:key @?url}
     [:a {:href @?url} (or @?text @?url)])

   ])

(ui/defview show-entry-field
  {:key (fn [?entry props]
          (-> @?entry :field-entry/field :field/id))}
  [?entry props]
  (let [field (:field-entry/field @?entry)
        props (merge (select-keys field [:field/label :field/hint :field/options])
                     (select-keys props [:field/can-edit?]))]
    (case (:field/type field)
      :field.type/video [video-field ('video/?url ?entry) props]
      :field.type/select [select-field ('select/?value ?entry) props]
      :field.type/link-list [link-list-field ('link-list/?links ?entry) props]
      :field.type/image-list [images-field ('image-list/?images ?entry) props]
      :field.type/prose [prose-field ?entry props]
      (str "no match" field))))

(defn show-image-list:card [field {:keys [image-list/images]}]
  (when-let [image (first images)]
    [:img.max-h-80 {:src (asset.ui/asset-src image :card)}]))

(comment
  (sb.server.datalevin/entity [:entity/id #uuid "b30e4733-0c90-3491-be07-99af22250f92"])
  (sch/kind #uuid "b30e4733-0c90-3491-be07-99af22250f92"))

(ui/defview show-prose:card [field {:as m :prose/keys [format string]}]
  (when-not (str/blank? string)
    (let [string (u/truncate-string string 140)]
      (case format
        :prose.format/html [sanitize/safe-html string]
        :prose.format/markdown [ui/show-markdown string]))))

(defn clean-url [url]
  (when url
    (-> url
        (str/replace #"^https?://(?:www)?" "")
        (u/truncate-string 20))))

(defn show-link-list:card [field {:keys [link-list/links]}]
  (v/x
    [:div.flex-v.gap-3
     (for [{:keys [text url]} links]
       [:a.text-black.underline {:href url} (or text (clean-url url))])]))

(ui/defview show-entry:card {:key (comp :field/id :field-entry/field)}
  [{:as entry :keys [field-entry/field]}]
  (case (:field/type field)
    :field.type/video [show-video-url (:video/url entry)]
    :field.type/select [show-select-value field (:select/value entry)]
    :field.type/link-list [show-link-list:card field entry]
    :field.type/image-list [show-image-list:card field entry]
    :field.type/prose [show-prose:card field entry]
    (str "no match" field)))

(defn make-entries-?field [init {:keys [entity/fields]}]
  (let [init (for [field fields]
               (merge #:field-entry{:field field} (get init (:field/id field))))]
    (io/form
      (->> (?entries :many
                     {:field-entry/field field-entry/?field
                      :image-list/images (image-list/?images :many {:entity/id ?id})
                      :video/url         video/?url
                      :select/value      select/?value
                      :link-list/links   (link-list/?links :many {:text link/?text
                                                                  :url  link/?url})
                      :prose/format      prose/?format
                      :prose/string      prose/?string}
                     :init init)
           (into {}
                 (map (juxt (comp :field/id :field-entry/field)
                            field.data/entry-value)))
           u/prune))))

(ui/defview tags-field
  {:make-?field (fn [init _props]
                  (io/field :many {:tag/id ?id}
                            :init init))}
  [?tags {:as props :keys [field/can-edit? membership/roles]}]
  (let [all-tags (-> (io/closest ?tags :db/id)
                     db/entity
                     :membership/entity
                     :entity/member-tags)
        by-id    (reduce #(assoc %1 (:tag/id %2) %2) {} all-tags)
        selected (into #{} (map :tag/id) @?tags)
        [editing? edit!] (h/use-state (and (empty? selected)
                                           (:role/self roles)))
        editing? (and can-edit? editing?)
        admin?   (:role/board-admin roles)
        to-add   (and can-edit? (->> all-tags
                                     (remove (fn [tag]
                                               (or (selected (:tag/id tag))
                                                   (and (:tag/restricted? tag)
                                                        (not admin?)))))
                                     seq))]
    [:div.flex-v.gap-1
     [:div.flex.flex-wrap.gap-2
      (doall (for [{:as ?tag :syms [?id]} ?tags
                   :let [{:tag/keys [id label color restricted?]} (by-id @?id)]]
               [:div.tag-md.cursor-default.gap-1.group
                {:key      id
                 :style    (color/color-pair color)
                 :on-click (when editing?
                             #(do (io/remove-many! ?tag)
                                  (entity.data/maybe-save-field ?tags)))}
                label
                (when (and editing? restricted?)
                  [icons/lock:micro "w-3 h-3 -mr-1"])
                (when editing?
                  [icons/x-mark "w-4 h-4 -mr-1 opacity-50 group-hover:opacity-100"])]))]
     (when can-edit?
       [:div.flex-v.gap-2
        (if-not editing?
          [:div.tag-md.px-1.hover:bg-gray-100.text-gray-400.hover:text-gray-700.cursor-default.mr-auto {:on-click #(edit! not)}
           (t :tr/edit-tags)]
          [:div.bg-gray-100.rounded-lg.border-gray-400.flex.items-stretch.mt-2.mr-auto
           (when to-add
             [:div.flex.flex-wrap.gap-2.p-3.-mr-3
              (for [{:tag/keys [id label color restricted?]} to-add]
                [:div.tag-md.cursor-default.group
                 {:key      id
                  :style    (color/color-pair color)
                  :on-click #(do (io/add-many! ?tags {:tag/id id})
                                 (entity.data/maybe-save-field ?tags))}
                 label
                 (when restricted?
                   [icons/lock:micro "w-3 h-3 -mr-1"])
                 [icons/plus-thick "w-4 h-4 -mr-1 opacity-50 group-hover:opacity-100"]])])
           [:div.hover:bg-gray-200.p-2.rounded.flex.items-center.m-1 {:on-click #(edit! not)} [icons/checkmark "flex-none"]]])
        [form.ui/show-field-messages ?tags]])]
    )
  ;; pass in a list of tags (from parent) to show. group-by (set @?tags).
  ;; show each tag,
  ;; if can-edit,
  ;;  - show an "x" for removing each tag,
  ;;  - show unused tags, click-to-add
  )

(ui/defview show-entries [member-fields field-entries]
  (when-let [entries (seq (into []
                                (keep
                                  (fn [field]
                                    (some-> (get field-entries (:field/id field))
                                            (assoc :field-entry/field field))))
                                member-fields))]
    (map show-entry:card entries)))

(ui/defview entries-field
  {:make-?field make-entries-?field}
  [{:syms [?entries]}
   {:as   props
    :keys [field/can-edit?]}]
  (doall (for [?entry (seq ?entries)
               :when (or can-edit?
                         (data/entry-value @?entry))]
           (show-entry-field ?entry props))))

;; TODO adapted from admin_ui/show-option merge?
(ui/defview show-request [{:as props :keys [request/use-order]}
                         {:as ?request :syms [?text]}]
  (let [{:keys [drag-handle-props drag-subject-props drop-indicator]} (use-order ?request)]
    [:div.flex.gap-2.items-center.group.relative.-ml-6.py-1
     drag-subject-props
     [:div
      drop-indicator
      [:div.flex.flex-none.items-center.justify-center.icon-gray
       (merge drag-handle-props
              {:class ["w-6 -mr-2"
                       "opacity-0 group-hover:opacity-100"
                       "cursor-drag"]})
       [icons/drag-dots]]]
     [text-field ?text {:field/label     false
                        :field/can-edit? true
                        :field/classes   {:wrapper "flex-auto"}
                        :class           "rounded-sm relative focus:z-2"}]
     [radix/dropdown-menu {:id      :field-request
                           :trigger [:button.p-1.relative.icon-gray.cursor-default.rounded.hover:bg-gray-200.self-stretch
                                     [icons/ellipsis-horizontal "w-4 h-4"]]
                           :items   [[{:on-select (fn [_]
                                                    (radix/simple-alert! {:message      (t :tr/remove?)
                                                                          :confirm-text (t :tr/remove)
                                                                          :confirm-fn   (fn []
                                                                                          (io/remove-many! ?request)
                                                                                          (p/do (entity.data/maybe-save-field ?request)
                                                                                                (radix/close-alert!)))}))}
                                      (t :tr/remove)]]}]]))

;; TODO adapted from admin_ui/options-editor, merge?
(ui/defview requests-editor
  [?requests props]
  (let [use-order (ui/use-orderable-parent ?requests {:axis :y})]
    [:div.col-span-2.flex-v.gap-3
     [:label.field-label (t :tr/requests)]
     (when (:loading? ?requests)
       [:div.loading-bar.absolute.h-1.top-0.left-0.right-0])
     (into [:div.flex-v]
           (map (partial show-request (assoc props :request/use-order use-order)) ?requests))
     (let [?new (h/use-memo #(io/field :init ""))]
       [:form.flex.gap-2 {:on-submit (fn [^js e]
                                       (.preventDefault e)
                                       (io/add-many! ?requests {'?text @?new})
                                       (io/try-submit+ ?new
                                                       (p/let [result (entity.data/maybe-save-field ?requests)]
                                                         (reset! ?new (:init ?new))
                                                         result)))}
        [text-field ?new {:placeholder     (t :tr/request-text)
                          :field/can-edit? true
                          :field/classes   {:wrapper "flex-auto"}}]
        [:button.btn.bg-white.px-3.py-1.shadow {:type "submit"} (t :tr/add-request)]])]))

(ui/defview show-requests [requests]
  (when (seq requests)
    [:div.field-wrapper
     [:div.field-label (t :tr/requests)]
     (into [:ul.list-disc.ml-4]
           (map (fn [{:keys [request/text]}]
                  [:li text]))
           requests)]))

(ui/defview requests-field
  {:make-?field (fn [init props]
                  (io/field :many {:request/text ?text}
                            :init init))}
  [?requests props]
  (if (:field/can-edit? props)
    [requests-editor ?requests props]
    [show-requests @?requests]))
