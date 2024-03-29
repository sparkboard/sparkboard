(ns org.sparkboard.slack.view
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [taoensso.timbre :as log]
            [sb.js-convert :refer [clj->json]]
            [org.sparkboard.slack.api :as slack.api]
            [org.sparkboard.slack.hiccup :as hiccup]
            [sb.util :as u]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; formatting helpers

(defn truncate [s max-len]
  (if (> (count s) max-len)
    (str (subs s 0 (dec max-len)) "…")
    s))

(defn format-channel-name [s]
  (-> s
      (str/replace #"\s+" "-")
      (str/lower-case)
      (str/replace #"[^\w\-_\d]" "")
      (truncate 70)))

(defn blockquote [text]
  (str "> " (str/replace text "\n" "\n> ")))

(defn link [text url]
  (str "<" url "|" text ">"))

(defn channel-link [id]
  (str "<#" id ">"))

(defn mention [user-id]
  (str "<@" user-id "> "))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; normalized handling of input values

(defn as-kw [x] (cond-> x (not (keyword? x)) (keyword)))

(defn action-value [action]
  (case (:type action)
    ("checkboxes"
     "multi_external_select"
     "multi_static_select") (->> (:selected_options action)
                                 (map :value)
                                 (into #{}))
    "multi_users_select" (set (:selected_users action))
    "multi_conversations_select" (set (:selected_conversations action))
    "multi_channels_select" (set (:selected_channels action))
    ("static_select"
     "external_select"
     "radio_buttons"
     "overflow") (-> action :selected_option :value)
    "plain_text_input" (:value action)
    "users_select" (:selected_user action)
    "datepicker" (:selected_date action)
    "button" nil
    (do
      (log/warn :not-parsing-action action)
      action)))

(defn actions-values [actions]
  (->> actions
       (reduce (fn [values action]
                 (assoc values (keyword (:action_id action)) (action-value action))) {})))

(defn input-values
  "Returns all of the normalized values from a view's input blocks"
  [view]
  (->> view
       :state
       :values
       vals
       (apply merge)
       (reduce-kv (fn [m k action]
                    (assoc m (keyword (name k)) (action-value action))) {})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API for making views with local state

(defonce registry
  ;; views generated with `defview` register callback functions here. these are necessarily global -
  ;; they must be triggered by HTTP requests sent from Slack.
  (atom {}))

(defn trigger [context]
  {:post [(some? %)]}
  (-> context :slack/payload :trigger_id))

(defn initial-state [view context]
  ;; can override the initial-state of the view by setting it in context
  (:initial-state context (:initial-state (meta view))))

(defn open!
  "Opens a modal"
  [view context]
  (slack.api/request! "views.open"
                      {:auth/token (:slack/bot-token context)}
                      {:view (view (assoc context :state (atom (initial-state view context))))
     :trigger_id (trigger context)}))

(defn push!
  "Pushes new modal to the stack"
  [view context]
  (slack.api/request! "views.push"
                      {:auth/token (:slack/bot-token context)}
                      {:trigger_id (trigger context)
     :view (view (assoc context :state (atom (initial-state view context))))}))

(defn replace!
  "Replaces modal at top of stack"
  [view context]
  (let [{:keys [hash id]} (-> context :slack/payload :view)]
    (slack.api/request! "views.update"
                        {:auth/token (:slack/bot-token context)}
                        {:view (view (assoc context :state (atom (initial-state view context))))
       :hash hash
       :view_id id
       :trigger_id (trigger context)})))

(defn home!
  "Set home tab for user-id"
  [view context user-id]
  {:pre [user-id]}
  (slack.api/request! "views.publish"
                      {:auth/token (:slack/bot-token context)}
                      {:view (view (assoc context :state (atom (initial-state view context))))
     :user_id user-id}))

(defn handle-home-opened!
  [view context]
  (slack.api/request! "views.publish"
                      {:auth/token (:slack/bot-token context)}
                      {:view (view (assoc context :state (atom (or (-> context :slack/payload :view :private_metadata)
                                                 (initial-state view context)))))
     :user_id (:slack/user-id context)}))

(def ^:dynamic *namespace*
  "Current view namespace, a string, used to resolve local IDs and create global IDs"
  nil)

(defn handle-block-action
  "Calls a block action with [context, state-atom, block-value]"
  [context view action-id action-fn]
  (let [{:keys [hash id] prev-state :private_metadata} (-> context :slack/payload :view)
        state-atom (atom prev-state)
        actions (-> context :slack/payload :actions)
        values (-> context :slack/payload :actions actions-values)
        value (get values action-id)
        _ (binding [*namespace* (:view-name (meta view))]
            (action-fn (assoc context
                         :state state-atom
                         :action-values values
                         :block-id (-> actions first :block_id)
                         :value (get values action-id)
                         :view view)))]
    (log/trace action-id value {:prev-state prev-state :next-state @state-atom})
    (when (and @state-atom (not= prev-state @state-atom))
      (slack.api/request! "views.update"
                          {:view (view (assoc context :state state-atom))
         :hash hash
         :view_id id
         :trigger_id (trigger context)}))))

(defn return-json [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (clj->json body)})

(defn handle-form-callback
  "Calls a form action with [context, state-atom, input-values]"
  [context view kind form-fn]
  (let [rsp-view (-> context :slack/payload :view)
        prev-state (:private_metadata rsp-view)
        state (atom prev-state)
        result (binding [*namespace* (:view-name (meta view))]
                 (form-fn (assoc context
                            :state state
                            :input-values (input-values rsp-view))))
        result (if (= prev-state @state)
                 result
                 [:update (view (assoc context :state state))])]
    (case kind
      :close result
      :submit (when-let [[action view] (u/guard result vector?)]
                (do (assert (and (#{:push :update} action)
                                 "on-submit should return [:push <view>] or [:update <view>] or nil"))
                    (return-json 200
                                 {:response_action action
                                  :view (hiccup/blocks view)}))))))

(defn id
  "Scopes an id to current view"
  ;; actions need globally-unique names, so we append them to their parent modal's name
  ([k]
   {:pre [*namespace*]}
   (id *namespace* k))
  ([view-name k]
   (if (simple-keyword? k)
     (keyword view-name (name k))
     k)))

(defn view-fn [{:as view-opts :keys [view-name render-fn]}]
  (with-meta
   (fn [ctx]
     (let [hiccup (binding [*namespace* view-name] (render-fn ctx))
           tag (first hiccup)]
       (cond-> hiccup
               (#{:modal} tag) (update-in [1 :callback-id] #(or % view-name))
               (#{:modal :home} tag) (with-meta ctx)
               (#{:modal :home} tag) (assoc-in [1 :private_metadata] (some-> (:state ctx) (deref))))))
   view-opts))

(defn make-view* [{:as view-opts
                   :keys [view-name
                          action-fns
                          on-submit
                          on-close]}]
  (let [view (with-meta (view-fn view-opts) (select-keys view-opts [:initial-state :actions :view-name]))
        handlers (merge (reduce-kv (fn [m action-name action-fn]
                                     (let [id (id view-name action-name)]
                                       (assoc m (as-kw id) #(handle-block-action % view id action-fn))))
                                   {}
                                   action-fns)
                        (when on-submit
                          {(keyword view-name "submit") ^:response-action? #(handle-form-callback % view :submit on-submit)})
                        (when on-close
                          {(keyword view-name "close") #(handle-form-callback % view :close on-submit)}))]
    (vary-meta view assoc :handlers handlers)))

(defmacro defview
  "Defines a modal or home surface. Behaves mostly like `defn`, returns a function which accepts 1 argument (context)
   and should return hiccup for the view. The function's metadata will contain the handlers registered for the view.
    Additional processing occurs:
    - a :state atom is included in the context, which can be swap!'d within action-callbacks to trigger a re-render
    - :action-id keys are processed to
      (a) support inline callback functions, which are passed the context including :state and :value
      (b) expand simple keywords into namespaced action_ids based on the parent view's name
    - handler functions for all action-functions are added to the registry
    - :on-submit callback will be triggered when modal is submitted and its context arg will include :input-values
    - a modal's callback_id will be set based on the name passed to `defview`
  See `docs/slack-views` for more details."
  [name-sym & args]
  (let [[doc args] (if (string? (first args))
                     [(first args) (rest args)]
                     [nil args])
        [options [argv & body]] (if (map? (first args))
                                  [(first args) (rest args)]
                                  [nil args])
        view-name (str name-sym)
        options (atom (set/rename-keys options {:actions ::actions}))
        body (walk/postwalk
              (fn [x]
                (if-not (map? x)
                  x
                  (cond (:action-id x)
                        (cond (map? (:action-id x))
                              (let [[action-k action-form] (first (:action-id x))
                                    action-id (id view-name action-k)]
                                (swap! options assoc-in [:action-fns action-id] action-form)
                                (swap! options assoc-in [:actions action-k] action-id)
                                (assoc x :action-id action-id))
                              (keyword? (:action-id x)) (update x :action-id (partial id view-name))
                              :else x)
                        (:on-submit x) (do (swap! options assoc :on-submit (:on-submit x))
                                           (dissoc x :on-submit))
                        (:on-close x) (do (swap! options assoc :on-close (:on-close x))
                                          (dissoc x :on-close))
                        :else x))) (last body))]
    (log/trace :actions (::actions @options))
    `(do (def ~name-sym
           (make-view* (merge {:view-name ~view-name
                               :render-fn (fn ~name-sym ~argv ~body)}
                              ~(deref options))))
         (swap! registry merge (:handlers (meta ~name-sym)))
         #'~name-sym)))

(defn register-handlers!
  "Add handlers to the view registry. Useful mainly for global events, which come in the form:
  events api -          :event/EVENT_NAME
  registered shortcut - :shortcut/CALLBACK_ID

  We also keep block_action callbacks in the registry using just the callback_id (keywordized)"
  [m]
  (swap! registry merge m))
