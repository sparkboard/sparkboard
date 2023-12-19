(ns sparkboard.app.field.ui
  (:require [applied-science.js-interop :as j]
            [clojure.string :as str]
            [clojure.set :as set]
            [inside-out.forms :as forms]
            [sparkboard.app.asset.ui :as asset.ui]
            [sparkboard.app.asset.data :as asset.data]
            [sparkboard.app.field-entry.data :as data]
            [sparkboard.routing :as routing]
            [sparkboard.app.views.ui :as ui]
            [sparkboard.icons :as icons]
            [sparkboard.app.views.radix :as radix]
            [yawn.hooks :as h]
            [yawn.view :as v]
            [sparkboard.client.sanitize :as sanitize]
            [sparkboard.app.form.ui :as form.ui]
            [sparkboard.color :as color]))

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

(defn show-prose [{:as m :prose/keys [format string]}]
  (when m
    (case format
      :prose.format/html [sanitize/safe-html string]
      :prose.format/markdown [ui/show-markdown string])))

(v/defview auto-size [props]
  (let [v!    (h/use-state "")
        props (merge props {:value     (:value props @v!)
                            :on-change (:on-change props
                                         #(reset! v! (j/get-in % [:target :value])))})]
    [:div.auto-size
     [:div.bg-black (select-keys props [:class :style])
      (str (:value props) " ")]
     [:textarea (assoc props :rows 1)]]))

(ui/defview checkbox-field
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field props]
  ;; TODO handle on-save
  (let [messages (forms/visible-messages ?field)
        loading? (:loading? ?field)
        props    (-> (v/merge-props (form.ui/?field-props ?field
                                                          (j/get-in [:target :checked])
                                                          props)
                                    {:type      "checkbox"
                                     :on-blur   (forms/blur-handler ?field)
                                     :on-focus  (forms/focus-handler ?field)
                                     :on-change #(let [value (.. ^js % -target -checked)]
                                                   (form.ui/maybe-save-field ?field props value))
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
       (when-let [label (:label ?field)]
         [:div.flex.items-center.h-5 label])
       (when (seq messages)
         (into [:div.text-gray-500] (map form.ui/view-message) messages))]]]))

(defn field-props [?field & [props]]
  (v/props (:props (meta ?field)) props))

(defn show-postfix [?field props]
  (when-let [postfix (or (:postfix props)
                         (:postfix (meta ?field))
                         (and (some-> (:persisted-value props)
                                      (not= (:value props)))
                              [icons/pencil-outline "w-4 h-4 text-txt/40"]))]
    [:div.pointer-events-none.absolute.inset-y-0.right-0.top-0.bottom-0.flex.items-center.p-2 postfix]))

(defn text-field
  "A text-input element that reads metadata from a ?field to display appropriately"
  [?field & [props]]
  (let [{:as   props
         :keys [inline?
                multi-line
                multi-paragraph
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
                               (form.ui/?field-props ?field
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
                                (ui/keydown-handler {(if multi-paragraph
                                                       :Meta-Enter
                                                       :Enter) #(when on-save
                                                                  (j/call % :preventDefault)
                                                                  (form.ui/maybe-save-field ?field props @?field))
                                                     :Escape   blur!
                                                     :Meta-.   cancel!})})]
    (v/x
      [:div.field-wrapper
       {:class wrapper-class}
       (when-not inline? (form.ui/show-label ?field (:label props)))
       [:div.flex-v.relative
        (if multi-line
          [auto-size (v/merge-props {:class "form-text w-full"} (form.ui/pass-props props))]
          [:input.form-text (form.ui/pass-props props)])
        (show-postfix ?field props)
        (when (:loading? ?field)
          [:div.loading-bar.absolute.bottom-0.left-0.right-0 {:class "h-[3px]"}])]
       (form.ui/show-field-messages ?field)])))

(defn wrap-prose [value]
  (when-not (str/blank? value)
    {:prose/format :prose.format/markdown
     :prose/string value}))

(def unwrap-prose :prose/string)

(ui/defview prose-field [?field props]
  ;; TODO
  ;; multi-line markdown editor with formatting
  (text-field ?field (merge {:wrap       wrap-prose
                             :unwrap     unwrap-prose
                             :multi-line true}
                            props)))

(ui/defview show-select [?field {:field/keys [label options]} entry]
  [:div.flex-v.gap-2
   [:label.field-label label]
   [radix/select-menu {:value      (:select/value @?field)
                       :id         (str (:entity/id entry))
                       :read-only? (:can-edit? ?field)
                       :options    (->> options
                                        (map (fn [{:field-option/keys [label value color]}]
                                               {:text  label
                                                :value value}))
                                        doall)}]])

(comment

  (defn youtube-embed [video-id]
    [:iframe#ytplayer {:type        "text/html" :width 640 :height 360
                       :frameborder 0
                       :src         (str "https://www.youtube.com/embed/" video-id)}])
  (video-field [:field.video/youtube-sdgurl "gMpYX2oev0M"])
  )

(ui/defview show-video [url]
  [:a.bg-black.w-full.aspect-video.flex.items-center.justify-center.group.relative
   {:href   url
    :target "_blank"
    :style  {:background-image    (asset.ui/css-url (:video/thumbnail (parse-video-url url)))
             :background-size     "cover"
             :background-position "center"}}
   [icons/external-link "absolute text-white top-2 right-2 icon-sm drop-shadow"]
   [icons/play-circle "icon-xl text-white drop-shadow-2xl transition-all group-hover:scale-110 "]])

(ui/defview video-field
  {:key (fn [?field] #?(:cljs (goog/getUid ?field)))}
  [?field {:as props :keys [can-edit?]}]
  (let [!editing? (h/use-state (nil? @?field))]
    [:div.field-wrapper
     ;; preview shows persisted value?
     [:div.flex.items-center
      [:div.flex-auto (form.ui/show-label ?field (:label props))]
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
       [show-video url])]))

(ui/defview select-field [?field {:as props :keys [label options]}]
  [:div.field-wrapper
   (form.ui/show-label ?field label)
   [radix/select-menu (-> (form.ui/?field-props ?field identity (assoc props
                                                                  :on-change #(form.ui/maybe-save-field ?field props @?field)))
                          (set/rename-keys {:on-change :on-value-change})
                          (assoc :can-edit? (:can-edit? props)
                                 :options (->> options
                                               (map (fn [{:field-option/keys [label value color]}]
                                                      {:text  label
                                                       :value value}))
                                               doall)))]
   (when (:loading? ?field)
     [:div.loading-bar.absolute.bottom-0.left-0.right-0 {:class "h-[3px]"}])])

(ui/defview color-field [?field props]
  (let [get-value (j/get-in [:target :value])]
    [:input.default-ring.default-ring-hover.rounded
     (-> (v/merge-props
           (form.ui/pass-props props)
           (form.ui/?field-props ?field get-value props)
           {:on-blur (fn [e]
                       (reset! ?field (get-value e))
                       (form.ui/maybe-save-field ?field props (get-value e)))
            :type    "color"})
         (update :value #(or % "#ffffff")))]))

(ui/defview image-field [?field props]
  (let [src            (asset.ui/asset-src @?field :card)
        loading?       (:loading? ?field)
        !selected-blob (h/use-state nil)
        !dragging?     (h/use-state false)
        thumbnail      (ui/use-loaded-image src @!selected-blob)
        on-file        (fn [file]
                         (forms/touch! ?field)
                         (reset! !selected-blob (js/URL.createObjectURL file))
                         (ui/with-submission [asset (routing/POST `asset.data/upload! (doto (js/FormData.)
                                                                                        (.append "files" file)))
                                              :form ?field]
                                             (reset! ?field asset)
                                             (form.ui/maybe-save-field ?field props asset)))
        !input         (h/use-ref)]
    ;; TODO handle on-save
    [:label.gap-2.flex-v.relative
     {:for (form.ui/field-id ?field)}
     (form.ui/show-label ?field (:label props))
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

(ui/defview images-field [?field {:as props :keys [label]}]
  (let [images (->> (:images/order @?field)
                    (map (fn [id]
                           {:url       (asset.ui/asset-src {:entity/id id} :card)
                            :entity/id id})))]
    (for [{:keys [entity/id url]} images]
      ;; TODO
      ;; upload image,
      ;; re-order images
      [:div.relative {:key url}
       [:div.inset-0.bg-black.absolute.opacity-10]
       [:img {:src url}]])))

(ui/defview show-entry
  {:key (comp :entity/id :field)}
  [{:keys [parent field entry can-edit?]}]
  (let [value  (data/entry-value field entry)
        ?field (h/use-memo #(forms/field :init (data/entry-value field entry) :label (:field/label field)))
        props  {:label     (:label field)
                :can-edit? can-edit?
                :on-save   (partial data/save-entry! nil (:entity/id parent) (:entity/id field))}]
    (case (:field/type field)
      :field.type/video [video-field ?field props]
      :field.type/select [select-field ?field (merge props
                                                     {:wrap            (fn [x] {:select/value x})
                                                      :unwrap          :select/value
                                                      :persisted-value value
                                                      :options         (:field/options field)})]
      :field.type/link-list [ui/pprinted value props]
      :field.type/image-list [images-field ?field props]
      :field.type/prose [prose-field ?field props]
      (str "no match" field))))