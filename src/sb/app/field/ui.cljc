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
            [promesa.core :as p]))

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
        [:div.h-5.w-5.inline-flex.items-center.justify-center.absolute
         [ui/loading:spinner "h-3 w-3"]])
      [:input.h-5.w-5.rounded.border-gray-300.text-primary
       (form.ui/pass-props props)]
      [:div.flex-v.gap-1.ml-2
       (when-let [label (form.ui/get-label ?field (:field/label props))]
         [:div.flex.items-center.h-5 label])
       (when (seq messages)
         (into [:div.text-gray-500] (map form.ui/view-message) messages))]]]))

(defn error-popover [anchor content]
  (v/x [radix/persistent-popover
        {:default-open? true
         :content       (v/x content)
         :classes       {:content "z-30 relative bg-white rounded shadow-lg px-3 py-2 border border-2 border-red-500"
                         :arrow   "fill-red-500"
                         :close   "rounded-full inline-flex items-center justify-center w-6 h-6 text-gray-500 absolute top-2 right-2"}}
        anchor]))

(defn with-messages-popover [?field anchor]
  (error-popover anchor (form.ui/show-field-messages ?field)))

(defn btn-progress-bar [classes]
  (v/x [:div.h-1.progress-bar.inset-x-0.top-0.absolute {:class classes}]))

(ui/defview action-btn [{:as   props
                         :keys [on-click
                                classes]} child]
  (let [!async-state (h/use-state nil)
        on-click     (fn [e]
                       (reset! !async-state {:loading? true})
                       (p/let [result (on-click e)]
                         (reset! !async-state (when (:error result) result))))
        {:keys [loading? error]} @!async-state]
    (cond-> [:div.btn.relative
             (-> props
                 (dissoc :classes)
                 (assoc :on-click (when-not loading? on-click))
                 (v/merge-props {:class (:btn classes)}))
             (when (:loading? @!async-state)
               [btn-progress-bar (:progress-bar classes)])
             child]
            error
            (error-popover error))))

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
                    :data-focused (:focused ?field)}]
    (when (or can-edit? (u/some-str (:value props)))
      [:div.field-wrapper
       (merge data-props {:class (:wrapper classes)})
       (form.ui/show-label ?field (:field/label props) (:label classes))
       [:div.flex-v.relative
        (with-messages-popover ?field
          [auto-size (-> (form.ui/pass-props props)
                         (v/merge-props
                           data-props
                           {:disabled    (not can-edit?)
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
                                                                      keybindings)))}))])
        (when-let [postfix (or (:field/postfix props)
                               (:field/postfix (meta ?field))
                               (and (some-> (entity.data/persisted-value ?field)
                                            (not= (:value props)))
                                    [icons/pencil-outline "w-4 h-4 text-txt/40"]))]
          [:div.pointer-events-none.absolute.inset-y-0.right-0.top-0.bottom-0.flex.items-center.p-2 postfix])

        (when (:loading? ?field)
          [:div.loading-bar.absolute.bottom-0.left-0.right-0 {:class "h-[3px]"}])]
       (when-let [hint (and (:focused ?field)
                            (:field/hint props))]
         [:div.text-gray-500.text-sm hint])])))

(defn wrap-prose [value]
  (when-not (str/blank? value)
    {:prose/format :prose.format/markdown
     :prose/string value}))

(def unwrap-prose :prose/string)

(ui/defview prose-field [{:as ?prose-field :prose/syms [?format ?string]} props]
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

(ui/defview show-video [url]
  [:a.bg-black.w-full.aspect-video.flex.items-center.justify-center.relative
   {:href   url
    :target "_blank"
    :style  {:background-image    (asset.ui/css-url (:video/thumbnail (parse-video-url url)))
             :background-size     "cover"
             :background-position "center"}}
   [:div.rounded.p-1.absolute.top-1.right-1.text-white {:class "hover:bg-black/20"} [icons/external-link " icon-lg drop-shadow"]]
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
       (when value
         [show-video value])
       (when can-edit?
         (text-field ?field (merge props
                                   {:field/label false
                                    :placeholder "YouTube or Vimeo url"})))])))

(ui/defview select-field [?field {:as props :field/keys [label options]}]
  [:div.field-wrapper
   (form.ui/show-label ?field label)
   [radix/select-menu (-> (form.ui/?field-props ?field (merge {:field/event->value identity}
                                                              props))
                          (set/rename-keys {:on-change :on-value-change})
                          (assoc :on-value-change (fn [v]
                                                    (reset! ?field v)
                                                    (entity.data/maybe-save-field ?field))
                                 :field/can-edit? (:field/can-edit? props)
                                 :field/options (->> options
                                                     (map (fn [{:field-option/keys [label value color]}]
                                                            {:text  label
                                                             :value value}))
                                                     doall)))]
   (when (:loading? ?field)
     [:div.loading-bar.absolute.bottom-0.left-0.right-0 {:class "h-[3px]"}])])

(ui/defview color-field
  ;; color field must be contained within a relative.overflow-hidden element, which it expands to fill.
  [?field props]
  [:input.default-ring.default-ring-hover.rounded
   (-> (form.ui/?field-props ?field
                             (merge props {:field/event->value (j/get-in [:target :value])
                                           :save-on-change?    true}))
       (v/merge-props {:style {:top      -10
                               :left     -10
                               :width    100
                               :height   100
                               :position "absolute"}} props)
       (assoc :type "color")
       (update :value #(or % "#ffffff"))
       (form.ui/pass-props))])

(ui/defview badge-form [{:keys [init
                                on-submit
                                close!]}]
  ;; TODO
  ;; - color picker should show colors already used in the board?
  (let [!ref    (h/use-ref)
        {:as ?badge :syms [?label ?color]} (h/use-memo #(io/form {:badge/label ?label
                                                                  :badge/color (?color :init "#dddddd")}
                                                                 :required [?label ?color]
                                                                 :init init)
                                                       (h/use-deps init))
        submit! #(on-submit ?badge close!)]
    [:el.relative Pop/Root {:open true :on-open-change #(do (prn :on-open-change %) (close!))}
     [:el Pop/Anchor]
     [:el Pop/Content {:as-child true}
      [:div.p-2.z-10 {:class radix/float-small}
       [:form.outline-none.flex.gap-2.items-stretch
        {:ref       !ref
         :on-submit (fn [e]
                      (.preventDefault e)
                      (submit!))}
        [text-field ?label {:placeholder       (t :tr/label)
                            :field/keybindings {:Enter submit!}
                            :field/multi-line? false
                            :field/can-edit?   true
                            :field/label       false}]
        [:div.relative.w-10.h-10.overflow-hidden.rounded.outline.outline-black.outline-1 [color-field ?color {:field/can-edit? true}]]
        [:button.flex.items-center {:type "submit"} [icons/checkmark "w-5 h-5 icon-gray"]]]
       (form.ui/show-field-messages (or (io/parent ?badge) ?badge))]]]))

(ui/defview badges-field* [?badges {:keys [member/roles]}]
  (let [board-admin? (:role/board-admin roles)
        !editing     (h/use-state nil)]
    [:div.flex.gap-1
     (for [{:as   ?badge
            :syms [?label ?color]} ?badges
           :let [bg    (or (u/some-str @?color) "#ffffff")
                 color (color/contrasting-text-color bg)
                 badge (v/x [:div.rounded.bg-badge.text-badge-txt.py-1.px-2.text-sm.inline-flex
                             {:key   @?label
                              :style {:background-color bg :color color}} @?label])]]
       (if board-admin?
         [radix/context-menu {:trigger [:div
                                        badge
                                        (when (= ?badge @!editing)
                                          [badge-form {:?badge    ?badge
                                                       :close!    #(reset! !editing nil)
                                                       :init      @?badge
                                                       :on-submit (fn [?new-badge close!]
                                                                    (reset! ?badge @?new-badge)
                                                                    (p/let [res (entity.data/maybe-save-field ?badge)]
                                                                      (when-not (:error res)
                                                                        (close!))))}])]
                              :items   [[radix/context-menu-item
                                         {:on-select (fn []
                                                       (io/remove-many! ?badge)
                                                       (entity.data/maybe-save-field ?badges))}
                                         (t :tr/remove)]
                                        [radix/context-menu-item
                                         {:on-select (fn [] (p/do (p/delay 0) (reset! !editing ?badge)))}
                                         (t :tr/edit)]]}]
         badge))
     (let [!creating-new (h/use-state false)]
       (when board-admin?
         [:div.inline-flex.text-sm.gap-1.items-center.rounded.hover:bg-gray-100.p-1
          {:on-click #(reset! !creating-new true)}
          (when-not (seq ?badges) [:span.cursor-default (t :tr/add-badge)])
          [icons/plus "w-4 h-4 icon-gray"]
          (when @!creating-new
            [badge-form {:on-submit (fn [?badge close!]
                                      (io/add-many! ?badges @?badge)
                                      (io/clear! ?badge)
                                      (p/let [res (entity.data/maybe-save-field ?badges)]
                                        (when-not (:error res)
                                          (close!))))
                         :init      {:badge/color "#dddddd"}
                         :close!    #(reset! !creating-new false)}])]))]))

(ui/defview badges-field [?badges {:as props :keys [member/roles]}]
  (when (or (seq ?badges)
            (:role/board-admin roles))
    (badges-field* ?badges props)))


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
      [:div.block.relative.rounded.cursor-pointer.flex.items-center.justify-center.rounded-lg
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

     [:div.block.absolute.inset-0.rounded.cursor-pointer.flex.items-center.justify-center.rounded-lg
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
        img (v/x [:img.object-contain.h-16.w-16.rounded.overflow-hidden.bg-gray-50.transition-all
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
  (let [!?current (h/use-state (first ?images))
        use-order (ui/use-orderable-parent ?images {:axis :x})
        [selected-url loading?] (some-> @!?current ('?id) deref (asset.ui/asset-src :card) ui/use-last-loaded)]
    [:div.field-wrapper
     (form.ui/show-label ?images label)
     (when selected-url
       [:div.relative.flex.items-center.justify-center {:key selected-url}
        (when loading? [icons/loading "w-4 h-4 text-txt/60 absolute top-2 right-2"])
        [:img.max-h-80 {:src selected-url}]])
     ;; thumbnails
     [:div.flex.gap-2.flex-wrap
      (when can-edit? [:div.relative.h-16.w-16.flex-none [add-image-button ?images]])
      (->> ?images
           (map (partial image-thumbnail
                         (merge props {:use-order use-order
                                       :!?current !?current
                                       :?images   ?images}))))]]))

(ui/defview link-list-field [?links {:field/keys [label]}]
  [:div.field-wrapper
   (form.ui/show-label ?links label)
   (for [{:syms [link/?text link/?url]} ?links]
     [:a {:href @?url} (or @?text @?url)])

   ])

(ui/defview show-entry
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

(defn make-field:entries [init {:keys [entity/fields]}]
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
                 (map (fn [{:as entry :keys [field-entry/field]}]
                        [(:field/id field) (dissoc entry :field-entry/field)])))
           u/prune))))

(ui/defview entries-field [{:syms [?entries]}
                           {:as   props
                            :keys [field/can-edit?]}]

  (doall (for [?entry (seq ?entries)
               :when (or can-edit?
                         (data/entry-value @?entry))]
           (show-entry ?entry props))))