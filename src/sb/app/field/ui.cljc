(ns sb.app.field.ui
  (:require [applied-science.js-interop :as j]
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
            [yawn.view :as v]))

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
     [:div.bg-black (select-keys props [:class :style])
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

(ui/defview text-field
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field props]
  (let [{:as         props
         :field/keys [multi-line?
                      wrap
                      unwrap
                      wrapper-class]
         :or         {wrap   identity
                      unwrap identity}} (merge props (:props (meta ?field)))
        blur!   (fn [e] (j/call-in e [:target :blur]))
        cancel! (fn [^js e]
                  (.preventDefault e)
                  (.stopPropagation e)
                  (reset! ?field (entity.data/persisted-value ?field))
                  (js/setTimeout #(blur! e) 0))
        props   (v/merge-props props
                               (form.ui/?field-props ?field
                                                     (merge {:field/event->value (j/get-in [:target :value])
                                                             :field/wrap         #(when-not (str/blank? %) %)
                                                             :field/unwrap       #(or % "")}
                                                            props))

                               {:class       ["pr-8 rounded default-ring"
                                              (when (:invalid (forms/types (forms/visible-messages ?field)))
                                                "outline-invalid")]
                                :placeholder (:placeholder props)
                                :on-key-down (let [save #(when (io/ancestor-by ?field :field/persisted?)
                                                           (j/call % :preventDefault)
                                                           (entity.data/maybe-save-field ?field))]
                                               (ui/keydown-handler (merge {:Meta-Enter save
                                                                           :Escape     cancel!
                                                                           :Meta-.     cancel!}
                                                                          (when-not multi-line?
                                                                            {:Enter save}))))})]
    (v/x
      [:div.field-wrapper
       {:class wrapper-class}
       (form.ui/show-label ?field (:field/label props))
       [:div.flex-v.relative
        [auto-size (v/merge-props {:class "form-text w-full"} (form.ui/pass-props props))]
        (when-let [postfix (or (:field/postfix props)
                               (:field/postfix (meta ?field))
                               (and (some-> (entity.data/persisted-value ?field)
                                            (not= (:value props)))
                                    [icons/pencil-outline "w-4 h-4 text-txt/40"]))]
          [:div.pointer-events-none.absolute.inset-y-0.right-0.top-0.bottom-0.flex.items-center.p-2 postfix])

        (when (:loading? ?field)
          [:div.loading-bar.absolute.bottom-0.left-0.right-0 {:class "h-[3px]"}])]
       (form.ui/show-field-messages ?field)])))

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

(ui/defview show-select [?field {:field/keys [label options can-edit?]} entry]
  [:div.flex-v.gap-2
   [:label.field-label label]
   [radix/select-menu {:value           (or @?field "")
                       :id              (str (:entity/id entry))
                       :field/can-edit? can-edit?
                       :field/options   (->> options
                                             (map (fn [{:field-option/keys [label value color]}]
                                                    {:text  label
                                                     :value (or value "")}))
                                             doall)}]])

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
  {:key (fn [?field] #?(:cljs (goog/getUid ?field)))}
  [?field {:as props :keys [field/can-edit?]}]
  (let [!editing? (h/use-state (nil? @?field))]
    [:div.field-wrapper
     ;; preview shows persisted value?
     [:div.flex.items-center
      [:div.flex-auto (form.ui/show-label ?field (:field/label props))]
      #_(when can-edit?
          [:div.place-self-end [:a {:on-click #(swap! !editing? not)}
                                [(if @!editing? icons/chevron-up icons/chevron-down) "icon-gray"]]])]
     (when-let [url (:video/url @?field)]
       [show-video url])
     (when can-edit?
       (text-field ?field (merge props
                                 {:field/label  false
                                  :placeholder  "YouTube or Vimeo url"
                                  :field/wrap   (partial hash-map :video/url)
                                  :field/unwrap :video/url})))]))

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

(ui/defview color-field [?field props]
  [:input.default-ring.default-ring-hover.rounded
   (-> (form.ui/?field-props ?field
                             (merge props {:field/event->value (j/get-in [:target :value])
                                           :save-on-change?    true}))
       (v/merge-props props)
       (assoc :type "color")
       (update :value #(or % "#ffffff"))
       (form.ui/pass-props))])

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

(ui/defview images-field [?images {:field/keys [label can-edit?]}]
  (for [{:syms [?id]} ?images
        :let [url (asset.ui/asset-src @?id :card)]]
    ;; TODO
    ;; upload image,
    ;; re-order images
    [:div.relative {:key url}
     [form.ui/show-label ?images label]
     [:div.inset-0.bg-black.absolute.opacity-10]
     [:img {:src url}]]))

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
        props (merge (select-keys field [:field/label :field/options])
                     (select-keys props [:field/can-edit?]))]
    (case (:field/type field)
      :field.type/video [video-field
                         ('video/?url ?entry)
                         props]
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
                      :image-list/images (image-list/?images :many {:entity/id (sch/unwrap-id ?id)})
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